package io.sentinel.platform.domain.model;

import java.time.Instant;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import io.sentinel.platform.common.event.Severity;
import io.sentinel.platform.common.event.SignalType;

/**
 * A signal that the correlation engine decided belongs to an incident.
 *
 * <p>{@code occurrences} is incremented in place when a duplicate arrives inside the dedup window,
 * so a flapping alert produces one row with a counter rather than ten thousand rows.
 */
@Entity
@Table(name = "incident_signal")
public class IncidentSignal extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "incident_id", nullable = false)
    private Incident incident;

    @Column(name = "fingerprint", nullable = false, updatable = false)
    private String fingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "signal_type", nullable = false)
    private SignalType signalType;

    @Column(name = "service_key", nullable = false)
    private String serviceKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false)
    private Severity severity;

    @Column(name = "summary", nullable = false, length = 1000)
    private String summary;

    /** Why the engine attached this signal, 0.0–1.0. Surfaced in the UI so operators can disagree. */
    @Column(name = "correlation_score", nullable = false)
    private double correlationScore;

    @Column(name = "occurrences", nullable = false)
    private int occurrences = 1;

    @Column(name = "first_seen_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    /** Labels as sent by the producer (region, cluster, version). Used for correlation scoring. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "labels", columnDefinition = "jsonb")
    private Map<String, String> labels = Map.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    private Map<String, Object> payload = Map.of();

    protected IncidentSignal() {}

    public IncidentSignal(
            String fingerprint,
            SignalType signalType,
            String serviceKey,
            Severity severity,
            String summary,
            double correlationScore,
            Instant occurredAt,
            Map<String, String> labels,
            Map<String, Object> payload) {
        this.fingerprint = fingerprint;
        this.signalType = signalType;
        this.serviceKey = serviceKey;
        this.severity = severity;
        this.summary = summary;
        this.correlationScore = correlationScore;
        this.occurredAt = occurredAt;
        this.lastSeenAt = occurredAt;
        this.labels = labels == null ? Map.of() : labels;
        this.payload = payload == null ? Map.of() : payload;
    }

    void assignTo(Incident incident) {
        this.incident = incident;
    }

    public void recordRepeat(Instant seenAt, int additionalOccurrences) {
        this.occurrences += Math.max(additionalOccurrences, 1);
        if (seenAt.isAfter(lastSeenAt)) {
            this.lastSeenAt = seenAt;
        }
    }

    public Incident getIncident() {
        return incident;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public SignalType getSignalType() {
        return signalType;
    }

    public String getServiceKey() {
        return serviceKey;
    }

    public Severity getSeverity() {
        return severity;
    }

    public String getSummary() {
        return summary;
    }

    public double getCorrelationScore() {
        return correlationScore;
    }

    public int getOccurrences() {
        return occurrences;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public Map<String, String> getLabels() {
        return labels;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }
}
