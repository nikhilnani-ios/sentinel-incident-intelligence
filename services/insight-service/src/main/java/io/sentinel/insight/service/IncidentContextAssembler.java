package io.sentinel.insight.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.sentinel.insight.prompt.IncidentContext;
import io.sentinel.platform.common.error.ResourceNotFoundException;
import io.sentinel.platform.domain.model.Deployment;
import io.sentinel.platform.domain.model.Incident;
import io.sentinel.platform.domain.model.IncidentSignal;
import io.sentinel.platform.domain.model.TimelineEntry;
import io.sentinel.platform.domain.repository.DeploymentRepository;
import io.sentinel.platform.domain.repository.IncidentDeploymentRepository;
import io.sentinel.platform.domain.repository.IncidentRepository;
import io.sentinel.platform.domain.repository.IncidentSignalRepository;
import io.sentinel.platform.domain.repository.ServiceDependencyRepository;
import io.sentinel.platform.domain.repository.TimelineEntryRepository;

/**
 * Gathers the evidence an analysis is built from, and fingerprints it.
 *
 * <p>The fingerprint is the interesting part. Analysis is the one expensive operation in the
 * platform, and an incident's page gets refreshed constantly while it is open. Hashing the exact
 * inputs — signals, timeline, deployments, severity — means a regeneration request is only paid for
 * when something has actually changed since the last one. Nothing about the wall clock goes into the
 * hash, or every request would look new.
 */
@Component
public class IncidentContextAssembler {

    private final IncidentRepository incidentRepository;
    private final IncidentSignalRepository signalRepository;
    private final TimelineEntryRepository timelineRepository;
    private final IncidentDeploymentRepository incidentDeploymentRepository;
    private final DeploymentRepository deploymentRepository;
    private final ServiceDependencyRepository dependencyRepository;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    public IncidentContextAssembler(
            IncidentRepository incidentRepository,
            IncidentSignalRepository signalRepository,
            TimelineEntryRepository timelineRepository,
            IncidentDeploymentRepository incidentDeploymentRepository,
            DeploymentRepository deploymentRepository,
            ServiceDependencyRepository dependencyRepository,
            Clock clock) {
        this.incidentRepository = incidentRepository;
        this.signalRepository = signalRepository;
        this.timelineRepository = timelineRepository;
        this.incidentDeploymentRepository = incidentDeploymentRepository;
        this.deploymentRepository = deploymentRepository;
        this.dependencyRepository = dependencyRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Bundle assemble(String tenantId, UUID incidentId) {
        Incident incident = incidentRepository
                .findByTenantIdAndId(tenantId, incidentId)
                .orElseThrow(() -> new ResourceNotFoundException("Incident", incidentId));

        List<IncidentSignal> signals = signalRepository.findByIncidentIdOrderByOccurredAtAsc(incidentId);
        List<TimelineEntry> timeline = timelineRepository.findByIncidentIdOrderByOccurredAtAsc(incidentId);
        List<IncidentContext.SuspectDeployment> deployments = deployments(incident);

        Set<String> affected = incident.affectedServiceKeySet();
        List<IncidentContext.DependencyEdge> topology = dependencyRepository.findByTenantId(tenantId).stream()
                .filter(edge -> affected.contains(edge.getSourceKey()) || affected.contains(edge.getTargetKey()))
                .map(edge -> new IncidentContext.DependencyEdge(
                        edge.getSourceKey(), edge.getTargetKey(), edge.getKind().name(), edge.getCriticality()))
                .toList();

        IncidentContext context = new IncidentContext(
                incident.getIncidentKey(),
                incident.getTitle(),
                incident.getSeverity().name(),
                incident.getStatus().name(),
                incident.getPrimaryServiceKey(),
                List.copyOf(affected),
                incident.getDetectedAt(),
                minutesOpen(incident),
                signals.stream()
                        .map(signal -> new IncidentContext.Signal(
                                signal.getSignalType().name(),
                                signal.getServiceKey(),
                                signal.getSeverity().name(),
                                signal.getSummary(),
                                signal.getOccurrences(),
                                signal.getOccurredAt()))
                        .toList(),
                timeline.stream()
                        .map(entry -> new IncidentContext.TimelineMoment(
                                entry.getKind().name(), entry.getMessage(), entry.getActor(), entry.getOccurredAt()))
                        .toList(),
                deployments,
                topology);

        return new Bundle(incident, context, hash(context));
    }

    private List<IncidentContext.SuspectDeployment> deployments(Incident incident) {
        var links = incidentDeploymentRepository.findByIncidentIdOrderBySuspicionScoreDesc(incident.getId());
        if (links.isEmpty()) {
            return List.of();
        }

        Map<UUID, Deployment> byId =
                deploymentRepository
                        .findAllById(links.stream()
                                .map(link -> link.getDeploymentId())
                                .toList())
                        .stream()
                        .collect(Collectors.toMap(Deployment::getId, Function.identity()));

        return links.stream()
                .map(link -> {
                    Deployment deployment = byId.get(link.getDeploymentId());
                    if (deployment == null) {
                        return null;
                    }
                    return new IncidentContext.SuspectDeployment(
                            deployment.getServiceKey(),
                            deployment.getVersionLabel(),
                            deployment.getCommitSha(),
                            deployment.getOccurredAt(),
                            Duration.between(deployment.getOccurredAt(), incident.getDetectedAt())
                                    .toMinutes(),
                            link.getSuspicionScore());
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private Long minutesOpen(Incident incident) {
        Instant end = incident.getResolvedAt() == null ? clock.instant() : incident.getResolvedAt();
        return Duration.between(incident.getDetectedAt(), end).toMinutes();
    }

    /**
     * Hashes the evidence, deliberately excluding anything that moves on its own. "Open for 40
     * minutes" is in the prompt but not in the hash — otherwise every minute would invalidate the
     * previous analysis and the cache would never hit.
     */
    private String hash(IncidentContext context) {
        StringBuilder material = new StringBuilder()
                .append(context.incidentKey())
                .append('|')
                .append(context.severity())
                .append('|')
                .append(context.status())
                .append('|')
                .append(String.join(",", context.affectedServices()));

        context.signals().forEach(signal -> material.append("|S:")
                .append(signal.serviceKey())
                .append(':')
                .append(signal.summary())
                .append(':')
                .append(signal.occurrences()));

        context.timeline().forEach(moment -> material.append("|T:")
                .append(moment.kind())
                .append(':')
                .append(moment.occurredAt().toEpochMilli()));

        context.deployments().forEach(deployment -> material.append("|D:")
                .append(deployment.serviceKey())
                .append(':')
                .append(deployment.version()));

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }

    /** The incident, the model-facing view of it, and the digest that decides whether to regenerate. */
    public record Bundle(Incident incident, IncidentContext context, String contextHash) {}
}
