package io.sentinel.platform.domain.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One immutable line in the incident timeline.
 *
 * <p>Append-only by design: the timeline is the record of what happened and doubles as the audit
 * log and the raw material for the generated postmortem. Nothing edits or deletes an entry.
 */
@Entity
@Table(name = "timeline_entry")
public class TimelineEntry extends BaseEntity {

    @Column(name = "incident_id", nullable = false, updatable = false)
    private UUID incidentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    private Kind kind;

    @Column(name = "message", nullable = false, length = 2000)
    private String message;

    /** Either a user id or a system component name such as {@code correlation-engine}. */
    @Column(name = "actor", nullable = false)
    private String actor;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata = Map.of();

    public enum Kind {
        DETECTED,
        SIGNAL_CORRELATED,
        SEVERITY_CHANGED,
        DEPLOYMENT_LINKED,
        ACKNOWLEDGED,
        ESCALATED,
        COMMENT,
        ANALYSIS_GENERATED,
        MITIGATED,
        RESOLVED
    }

    protected TimelineEntry() {}

    public TimelineEntry(UUID incidentId, Kind kind, String message, String actor, Instant occurredAt) {
        this.incidentId = incidentId;
        this.kind = kind;
        this.message = message;
        this.actor = actor;
        this.occurredAt = occurredAt;
    }

    public TimelineEntry withMetadata(Map<String, Object> metadata) {
        this.metadata = metadata == null ? Map.of() : metadata;
        return this;
    }

    public UUID getIncidentId() {
        return incidentId;
    }

    public Kind getKind() {
        return kind;
    }

    public String getMessage() {
        return message;
    }

    public String getActor() {
        return actor;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }
}
