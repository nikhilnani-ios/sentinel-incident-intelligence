package io.sentinel.correlation.service;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import io.sentinel.correlation.config.CorrelationProperties;
import io.sentinel.correlation.graph.ServiceGraph;
import io.sentinel.platform.common.event.Severity;
import io.sentinel.platform.common.event.SignalEnvelope;
import io.sentinel.platform.domain.model.Incident;
import io.sentinel.platform.domain.model.IncidentSignal;

/**
 * Scores how likely it is that a signal belongs to an existing incident.
 *
 * <p>Deliberately a pure function of its inputs: no repositories, no clock, no Redis. That makes
 * the scoring rules exhaustively testable, which matters because this is the component that decides
 * whether an on-call engineer sees one incident or forty.
 *
 * <p>Four factors, each normalised to 0..1 and then weighted:
 *
 * <ol>
 *   <li><b>Graph proximity</b> — how tightly the signal's service is coupled to the incident's.
 *       The dominant factor: unrelated services producing alerts at the same moment is a
 *       coincidence, a database and its three callers alerting together is one incident.
 *   <li><b>Time proximity</b> — exponential decay over the correlation window. A cascade propagates
 *       in seconds, so the half-life is short by design.
 *   <li><b>Severity affinity</b> — two CRITICALs are more likely to share a cause than a CRITICAL
 *       and an INFO.
 *   <li><b>Label overlap</b> — Jaccard similarity. Same region and same cluster is strong evidence;
 *       it is what separates "us-east-1 is degraded" from "us-west-2 is fine".
 * </ol>
 */
@Component
public class CorrelationScorer {

    /** Weight remaining after one half-life; drives the exponential time decay. */
    private static final double TIME_HALF_LIFE_FRACTION = 0.25;

    private final CorrelationProperties properties;

    public CorrelationScorer(CorrelationProperties properties) {
        this.properties = properties;
    }

    public Score score(SignalEnvelope signal, Incident incident, ServiceGraph graph) {
        double graphProximity = graphProximity(signal.serviceKey(), incident, graph);
        double timeProximity = timeProximity(signal, incident);
        double severityAffinity = severityAffinity(signal.severity(), incident.getSeverity());
        double labelOverlap = labelOverlap(signal.labels(), incident);

        CorrelationProperties.Weights weights = properties.weights();
        double weighted = (graphProximity * weights.graphProximity())
                + (timeProximity * weights.timeProximity())
                + (severityAffinity * weights.severityAffinity())
                + (labelOverlap * weights.labelOverlap());

        double normalised = weights.total() == 0 ? 0 : weighted / weights.total();
        return new Score(normalised, graphProximity, timeProximity, severityAffinity, labelOverlap);
    }

    /**
     * Strongest connection between the signal's service and anything already on the incident.
     *
     * <p>Checking every affected service, not just the primary one, is what lets a cascade grow
     * outward: once cart-service joins an incident that started in the database, a later alert from
     * checkout correlates through cart even though it is two hops from the origin.
     */
    private double graphProximity(String serviceKey, Incident incident, ServiceGraph graph) {
        double best = 0.0;
        for (String affected : incident.affectedServiceKeySet()) {
            if (serviceKey.equals(affected)) {
                return 1.0;
            }
            best = Math.max(best, graph.relatedness(serviceKey, affected, properties.maxGraphDepth()));
        }
        return best;
    }

    /**
     * Exponential decay: 1.0 at the moment of detection, {@code TIME_HALF_LIFE_FRACTION} of the way
     * through the window it has halved, and it reaches zero at the window edge.
     */
    private double timeProximity(SignalEnvelope signal, Incident incident) {
        Duration window = properties.correlationWindow();
        Duration gap =
                Duration.between(incident.getDetectedAt(), signal.occurredAt()).abs();

        if (gap.compareTo(window) >= 0) {
            return 0.0;
        }
        double halfLife = window.toMillis() * TIME_HALF_LIFE_FRACTION;
        return Math.pow(0.5, gap.toMillis() / halfLife);
    }

    /**
     * Two pageable signals reinforce each other; a large gap in urgency suggests separate problems.
     * Scaled by the widest possible gap so the result is always 0..1.
     */
    private double severityAffinity(Severity signalSeverity, Severity incidentSeverity) {
        int distance = Math.abs(signalSeverity.rank() - incidentSeverity.rank());
        int widest = Severity.INFO.rank() - Severity.CRITICAL.rank();
        double affinity = 1.0 - ((double) distance / widest);

        boolean bothPageable = signalSeverity.isPageable() && incidentSeverity.isPageable();
        return bothPageable ? Math.min(1.0, affinity + 0.15) : affinity;
    }

    /** Jaccard similarity of the label sets, comparing full key=value pairs. */
    private double labelOverlap(Map<String, String> signalLabels, Incident incident) {
        Set<String> signalPairs = pairs(signalLabels);
        Set<String> incidentPairs = pairs(incidentLabels(incident));

        if (signalPairs.isEmpty() || incidentPairs.isEmpty()) {
            // No labels is not evidence either way; stay neutral rather than penalising.
            return 0.5;
        }

        Set<String> intersection = new HashSet<>(signalPairs);
        intersection.retainAll(incidentPairs);

        Set<String> union = new HashSet<>(signalPairs);
        union.addAll(incidentPairs);

        return (double) intersection.size() / union.size();
    }

    /**
     * Labels of the most recent signal on the incident — the best available proxy for "what this
     * incident currently looks like", since an evolving incident's labels drift as it spreads.
     */
    private Map<String, String> incidentLabels(Incident incident) {
        return incident.getSignals().stream()
                .reduce((first, second) -> second.getLastSeenAt().isAfter(first.getLastSeenAt()) ? second : first)
                .map(IncidentSignal::getLabels)
                .orElseGet(Map::of);
    }

    private Set<String> pairs(Map<String, String> labels) {
        Set<String> pairs = new HashSet<>();
        labels.forEach((key, value) -> pairs.add(key + '=' + value));
        return pairs;
    }

    /**
     * The verdict, with its components kept so the UI can explain why a signal was grouped — an
     * unexplained grouping is one an operator cannot trust or override.
     */
    public record Score(
            double total, double graphProximity, double timeProximity, double severityAffinity, double labelOverlap) {

        public String explain() {
            return "graph=%.2f time=%.2f severity=%.2f labels=%.2f"
                    .formatted(graphProximity, timeProximity, severityAffinity, labelOverlap);
        }
    }
}
