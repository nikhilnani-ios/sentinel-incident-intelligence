package io.sentinel.incident.api.dto;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.sentinel.platform.common.event.IncidentStatus;
import io.sentinel.platform.common.event.Severity;
import io.sentinel.platform.common.event.SignalType;
import io.sentinel.platform.domain.model.Incident;
import io.sentinel.platform.domain.model.IncidentSignal;
import io.sentinel.platform.domain.model.TimelineEntry;

/** Read models for the incident API. Mapping lives with the DTO so entities never leak outward. */
public final class IncidentResponses {

    private IncidentResponses() {}

    /** Row shape for the incident list. Deliberately small — the feed can be thousands of rows. */
    public record Summary(
            UUID id,
            String incidentKey,
            String title,
            IncidentStatus status,
            Severity severity,
            String primaryServiceKey,
            Set<String> affectedServiceKeys,
            Instant detectedAt,
            Instant acknowledgedAt,
            Instant resolvedAt,
            int escalationLevel,
            int signalCount,
            Long timeToAcknowledgeSeconds,
            Long timeToResolveSeconds) {

        public static Summary from(Incident incident) {
            return new Summary(
                    incident.getId(),
                    incident.getIncidentKey(),
                    incident.getTitle(),
                    incident.getStatus(),
                    incident.getSeverity(),
                    incident.getPrimaryServiceKey(),
                    incident.affectedServiceKeySet(),
                    incident.getDetectedAt(),
                    incident.getAcknowledgedAt(),
                    incident.getResolvedAt(),
                    incident.getEscalationLevel(),
                    incident.getSignalCount(),
                    seconds(incident.timeToAcknowledge()),
                    seconds(incident.timeToResolve()));
        }

        private static Long seconds(Duration duration) {
            return duration == null ? null : duration.toSeconds();
        }
    }

    public record Detail(
            Summary summary,
            String description,
            String acknowledgedBy,
            String resolvedBy,
            String escalationPolicyKey,
            List<Signal> signals,
            List<Timeline> timeline,
            List<SuspectDeployment> suspectDeployments,
            List<GraphNode> dependencyGraph,
            Insight rootCauseAnalysis) {}

    public record Signal(
            UUID id,
            SignalType type,
            String serviceKey,
            Severity severity,
            String summary,
            double correlationScore,
            int occurrences,
            Instant firstSeenAt,
            Instant lastSeenAt,
            Map<String, String> labels,
            Map<String, Object> detail) {

        public static Signal from(IncidentSignal signal) {
            return new Signal(
                    signal.getId(),
                    signal.getSignalType(),
                    signal.getServiceKey(),
                    signal.getSeverity(),
                    signal.getSummary(),
                    signal.getCorrelationScore(),
                    signal.getOccurrences(),
                    signal.getOccurredAt(),
                    signal.getLastSeenAt(),
                    signal.getLabels(),
                    signal.getPayload());
        }
    }

    public record Timeline(
            UUID id,
            TimelineEntry.Kind kind,
            String message,
            String actor,
            Instant occurredAt,
            Map<String, Object> metadata) {

        public static Timeline from(TimelineEntry entry) {
            return new Timeline(
                    entry.getId(),
                    entry.getKind(),
                    entry.getMessage(),
                    entry.getActor(),
                    entry.getOccurredAt(),
                    entry.getMetadata());
        }
    }

    public record SuspectDeployment(
            UUID deploymentId,
            String serviceKey,
            String version,
            String commitSha,
            String environment,
            String deployedBy,
            String changelogUrl,
            Instant occurredAt,
            double suspicionScore,
            String rationale) {}

    /** One node of the incident's local topology, with why it matters to this incident. */
    public record GraphNode(
            String serviceKey,
            String displayName,
            String tier,
            boolean affected,
            boolean isPrimary,
            double impactWeight,
            List<String> dependsOn) {}

    public record Insight(
            String headline,
            String body,
            double confidence,
            String model,
            List<Map<String, Object>> hypotheses,
            Instant generatedAt) {}
}
