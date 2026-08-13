package io.sentinel.platform.common.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Fan-out event published whenever an incident changes.
 *
 * <p>Consumed by the incident service to push SSE frames to connected browsers, and by any future
 * notifier (Slack, email, SMS) without those notifiers needing database access.
 */
public record IncidentEvent(
        UUID incidentId,
        String tenantId,
        Change change,
        IncidentStatus status,
        Severity severity,
        String title,
        String primaryServiceKey,
        List<String> affectedServiceKeys,
        String actor,
        Instant occurredAt) {

    public enum Change {
        CREATED,
        SIGNAL_ATTACHED,
        SEVERITY_RAISED,
        ACKNOWLEDGED,
        ESCALATED,
        MITIGATED,
        RESOLVED,
        ANALYSIS_READY
    }

    public IncidentEvent {
        affectedServiceKeys = affectedServiceKeys == null ? List.of() : List.copyOf(affectedServiceKeys);
    }
}
