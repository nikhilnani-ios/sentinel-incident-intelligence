package io.sentinel.correlation.service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.sentinel.correlation.config.CorrelationProperties;
import io.sentinel.correlation.graph.ServiceGraph;
import io.sentinel.correlation.graph.ServiceGraphProvider;
import io.sentinel.correlation.publisher.IncidentEventPublisher;
import io.sentinel.platform.common.event.IncidentEvent;
import io.sentinel.platform.common.event.SignalEnvelope;
import io.sentinel.platform.domain.model.Incident;
import io.sentinel.platform.domain.model.IncidentSignal;
import io.sentinel.platform.domain.model.TimelineEntry;
import io.sentinel.platform.domain.repository.IncidentRepository;
import io.sentinel.platform.domain.repository.IncidentSignalRepository;
import io.sentinel.platform.domain.repository.TimelineEntryRepository;

/**
 * Turns a stream of signals into a small number of incidents.
 *
 * <p>The pipeline for each signal, cheapest check first:
 *
 * <pre>
 *   dedup (Redis, ~0.1ms)
 *     └── hit  → fold into the incident that fingerprint already owns
 *     └── miss → find candidate incidents in the blast radius (indexed query)
 *                  └── best score above threshold → attach
 *                  └── otherwise, if the signal warrants it → open a new incident
 * </pre>
 *
 * <p>Everything runs inside one transaction per signal, and the candidate incident is locked with
 * {@code SELECT ... FOR UPDATE} before mutation. Signals for one service always land on the same
 * Kafka partition, but a cascade spans services and therefore partitions, so two consumer threads
 * genuinely can race for the same incident.
 */
@Service
public class CorrelationEngine {

    private static final Logger log = LoggerFactory.getLogger(CorrelationEngine.class);

    private final IncidentRepository incidentRepository;
    private final IncidentSignalRepository signalRepository;
    private final TimelineEntryRepository timelineRepository;
    private final DeduplicationService deduplicationService;
    private final CorrelationScorer scorer;
    private final ServiceGraphProvider graphProvider;
    private final IncidentFactory incidentFactory;
    private final DeploymentCorrelator deploymentCorrelator;
    private final SignalTriage triage;
    private final IncidentEventPublisher eventPublisher;
    private final CorrelationProperties properties;
    private final MeterRegistry meterRegistry;
    private final Timer pipelineTimer;

    @SuppressWarnings("java:S107") // Wiring-heavy orchestrator; each collaborator is used.
    public CorrelationEngine(
            IncidentRepository incidentRepository,
            IncidentSignalRepository signalRepository,
            TimelineEntryRepository timelineRepository,
            DeduplicationService deduplicationService,
            CorrelationScorer scorer,
            ServiceGraphProvider graphProvider,
            IncidentFactory incidentFactory,
            DeploymentCorrelator deploymentCorrelator,
            SignalTriage triage,
            IncidentEventPublisher eventPublisher,
            CorrelationProperties properties,
            MeterRegistry meterRegistry) {
        this.incidentRepository = incidentRepository;
        this.signalRepository = signalRepository;
        this.timelineRepository = timelineRepository;
        this.deduplicationService = deduplicationService;
        this.scorer = scorer;
        this.graphProvider = graphProvider;
        this.incidentFactory = incidentFactory;
        this.deploymentCorrelator = deploymentCorrelator;
        this.triage = triage;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.pipelineTimer = Timer.builder("sentinel.correlation.pipeline")
                .description("End-to-end time to correlate one signal")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    @Transactional
    public Outcome correlate(SignalEnvelope signal) {
        return pipelineTimer.record(() -> runPipeline(signal));
    }

    private Outcome runPipeline(SignalEnvelope signal) {
        DeduplicationService.Verdict verdict = deduplicationService.classify(signal);

        if (triage.isResolutionSignal(signal)) {
            // Let the fingerprint fire again after the underlying alert clears.
            deduplicationService.forget(verdict.fingerprint());
        }

        if (verdict.suppressed() && verdict.incidentId().isPresent()) {
            Optional<Outcome> folded = foldIntoExisting(signal, verdict);
            if (folded.isPresent()) {
                return folded.get();
            }
            // The cached incident vanished (resolved and pruned); fall through and re-correlate.
        }

        ServiceGraph graph = graphProvider.forTenant(signal.tenantId());
        Optional<Scored> best = findBestCandidate(signal, graph);

        if (best.isPresent() && best.get().score().total() >= properties.attachThreshold()) {
            return attachToIncident(signal, verdict.fingerprint(), best.get());
        }

        if (!triage.canOpenIncident(signal)) {
            count("dropped", signal.type().name());
            log.debug("Signal {} is not incident-worthy and matched nothing open", signal.eventId());
            return Outcome.discarded();
        }

        return openIncident(signal, verdict.fingerprint(), graph);
    }

    // ------------------------------------------------------------------ dedup fast path

    private Optional<Outcome> foldIntoExisting(SignalEnvelope signal, DeduplicationService.Verdict verdict) {
        UUID incidentId = verdict.incidentId().orElseThrow();
        Optional<Incident> maybeIncident = incidentRepository.lockById(incidentId);
        if (maybeIncident.isEmpty() || maybeIncident.get().getStatus().isTerminal()) {
            return Optional.empty();
        }

        Incident incident = maybeIncident.get();
        signalRepository
                .findByIncidentAndFingerprint(incidentId, verdict.fingerprint())
                .ifPresent(existing -> existing.recordRepeat(signal.occurredAt(), occurrencesOf(signal)));

        if (incident.raiseSeverityTo(signal.severity())) {
            recordSeverityRaise(incident, signal);
        }

        count("deduplicated", signal.type().name());
        return Optional.of(Outcome.deduplicated(incident));
    }

    // ------------------------------------------------------------------ correlation

    private Optional<Scored> findBestCandidate(SignalEnvelope signal, ServiceGraph graph) {
        List<String> blastRadius = blastRadiusOf(signal, graph);
        Instant since = signal.occurredAt().minus(properties.correlationWindow());

        return incidentRepository.findCorrelationCandidates(signal.tenantId(), blastRadius, since).stream()
                .map(incident -> new Scored(incident, scorer.score(signal, incident, graph)))
                .max(Comparator.comparingDouble(scored -> scored.score().total()));
    }

    /**
     * The set of services this signal could plausibly relate to: itself, whatever it depends on, and
     * whatever depends on it. Bounding the candidate query this way keeps it index-friendly instead
     * of scanning every open incident.
     */
    private List<String> blastRadiusOf(SignalEnvelope signal, ServiceGraph graph) {
        int depth = properties.maxGraphDepth();
        return java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(signal.serviceKey()),
                        java.util.stream.Stream.concat(
                                graph.dependenciesOf(signal.serviceKey(), depth).keySet().stream(),
                                graph.blastRadius(signal.serviceKey(), depth).keySet().stream()))
                .distinct()
                .toList();
    }

    private Outcome attachToIncident(SignalEnvelope signal, String fingerprint, Scored scored) {
        Incident incident = incidentRepository
                .lockById(scored.incident().getId())
                .orElseThrow(() -> new IllegalStateException("Incident %s disappeared mid-correlation"
                        .formatted(scored.incident().getId())));

        if (!triage.canAttachToIncident(signal)) {
            return Outcome.discarded();
        }

        IncidentSignal incidentSignal =
                incidentFactory.newSignal(signal, fingerprint, scored.score().total());
        incident.attach(incidentSignal);
        deduplicationService.remember(fingerprint, incident.getId());

        timelineRepository.save(new TimelineEntry(
                        incident.getId(),
                        TimelineEntry.Kind.SIGNAL_CORRELATED,
                        "%s from %s (%s)"
                                .formatted(
                                        signal.payload().summary(),
                                        signal.serviceKey(),
                                        scored.score().explain()),
                        "correlation-engine",
                        signal.occurredAt())
                .withMetadata(Map.of(
                        "correlationScore", scored.score().total(),
                        "serviceKey", signal.serviceKey(),
                        "signalType", signal.type().name())));

        boolean severityRaised = incident.raiseSeverityTo(signal.severity());
        if (severityRaised) {
            recordSeverityRaise(incident, signal);
        }

        incidentRepository.save(incident);
        eventPublisher.publish(incident, IncidentEvent.Change.SIGNAL_ATTACHED, "correlation-engine");

        count("attached", signal.type().name());
        log.info(
                "Attached {} signal from {} to {} (score {})",
                signal.type(),
                signal.serviceKey(),
                incident.getIncidentKey(),
                String.format("%.2f", scored.score().total()));

        return Outcome.attached(incident, scored.score());
    }

    // ------------------------------------------------------------------ creation

    private Outcome openIncident(SignalEnvelope signal, String fingerprint, ServiceGraph graph) {
        Incident incident = incidentFactory.newIncident(signal);
        incident.attach(incidentFactory.newSignal(signal, fingerprint, 1.0));

        // The whole blast radius is recorded up front so responders immediately see who else is at
        // risk, before any of those services has produced a signal of its own.
        graph.blastRadius(signal.serviceKey(), properties.maxGraphDepth()).values().stream()
                .filter(reach -> reach.weight() >= 0.5)
                .forEach(reach -> incident.addAffectedService(reach.serviceKey()));

        Incident saved = incidentRepository.save(incident);
        deduplicationService.remember(fingerprint, saved.getId());

        timelineRepository.save(new TimelineEntry(
                        saved.getId(),
                        TimelineEntry.Kind.DETECTED,
                        "Incident opened from %s".formatted(signal.payload().summary()),
                        "correlation-engine",
                        signal.occurredAt())
                .withMetadata(Map.of(
                        "serviceKey", signal.serviceKey(),
                        "severity", signal.severity().name(),
                        "eventId", signal.eventId())));

        deploymentCorrelator.linkSuspects(saved, graph);
        eventPublisher.publish(saved, IncidentEvent.Change.CREATED, "correlation-engine");

        count("created", signal.type().name());
        log.info(
                "Opened {} [{}] for {} covering {} services",
                saved.getIncidentKey(),
                saved.getSeverity(),
                saved.getPrimaryServiceKey(),
                saved.affectedServiceKeySet().size());

        return Outcome.created(saved);
    }

    // ------------------------------------------------------------------ helpers

    private void recordSeverityRaise(Incident incident, SignalEnvelope signal) {
        timelineRepository.save(new TimelineEntry(
                incident.getId(),
                TimelineEntry.Kind.SEVERITY_CHANGED,
                "Severity raised to %s by %s"
                        .formatted(incident.getSeverity(), signal.payload().summary()),
                "correlation-engine",
                signal.occurredAt()));
        eventPublisher.publish(incident, IncidentEvent.Change.SEVERITY_RAISED, "correlation-engine");
    }

    private int occurrencesOf(SignalEnvelope signal) {
        return signal.payload() instanceof io.sentinel.platform.common.event.LogPayload logPayload
                ? logPayload.occurrences()
                : 1;
    }

    private void count(String action, String signalType) {
        Counter.builder("sentinel.correlation.outcome")
                .tag("action", action)
                .tag("signal_type", signalType)
                .description("Correlation decisions by outcome")
                .register(meterRegistry)
                .increment();
    }

    private record Scored(Incident incident, CorrelationScorer.Score score) {}

    /** What the engine decided, returned for logging and tests rather than consumed by callers. */
    public record Outcome(Action action, Incident incident, CorrelationScorer.Score score) {

        public enum Action {
            CREATED,
            ATTACHED,
            DEDUPLICATED,
            DISCARDED
        }

        static Outcome created(Incident incident) {
            return new Outcome(Action.CREATED, incident, null);
        }

        static Outcome attached(Incident incident, CorrelationScorer.Score score) {
            return new Outcome(Action.ATTACHED, incident, score);
        }

        static Outcome deduplicated(Incident incident) {
            return new Outcome(Action.DEDUPLICATED, incident, null);
        }

        static Outcome discarded() {
            return new Outcome(Action.DISCARDED, null, null);
        }
    }
}
