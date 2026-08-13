package io.sentinel.insight.prompt;

import java.time.Instant;
import java.util.List;

/**
 * Everything the model is allowed to see about an incident.
 *
 * <p>An explicit record rather than "serialise the entities": the prompt is a contract, and letting
 * entities leak into it means an unrelated schema change silently rewrites what the model reads.
 * It also keeps the boundary auditable — this is the exact list of fields that leave the platform.
 */
public record IncidentContext(
        String incidentKey,
        String title,
        String severity,
        String status,
        String primaryService,
        List<String> affectedServices,
        Instant detectedAt,
        Long minutesOpen,
        List<Signal> signals,
        List<TimelineMoment> timeline,
        List<SuspectDeployment> deployments,
        List<DependencyEdge> topology) {

    public record Signal(
            String type, String serviceKey, String severity, String summary, int occurrences, Instant firstSeenAt) {}

    public record TimelineMoment(String kind, String message, String actor, Instant occurredAt) {}

    public record SuspectDeployment(
            String serviceKey,
            String version,
            String commitSha,
            Instant deployedAt,
            long minutesBeforeDetection,
            double suspicionScore) {}

    public record DependencyEdge(String source, String target, String kind, double criticality) {}

    public SuspectDeployment mostSuspiciousDeployment() {
        return deployments.stream()
                .max(java.util.Comparator.comparingDouble(SuspectDeployment::suspicionScore))
                .orElse(null);
    }
}
