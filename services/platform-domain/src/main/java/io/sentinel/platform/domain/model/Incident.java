package io.sentinel.platform.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import io.sentinel.platform.common.error.InvalidStateTransitionException;
import io.sentinel.platform.common.event.IncidentStatus;
import io.sentinel.platform.common.event.Severity;

/**
 * The aggregate root. All lifecycle rules live here rather than in the service layer, so an illegal
 * transition is impossible to express regardless of which caller reaches the entity.
 */
@Entity
@Table(name = "incident")
public class Incident extends BaseEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private String tenantId;

    /** Human-facing identifier, e.g. {@code INC-2481}. Allocated from a Postgres sequence. */
    @Column(name = "incident_key", nullable = false, updatable = false)
    private String incidentKey;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "summary", length = 4000)
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private IncidentStatus status = IncidentStatus.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false)
    private Severity severity;

    @Column(name = "primary_service_key", nullable = false)
    private String primaryServiceKey;

    /** Denormalised blast radius, maintained by the correlation engine for fast list queries. */
    @Column(name = "affected_service_keys", nullable = false)
    private String affectedServiceKeys = "";

    @Column(name = "detected_at", nullable = false, updatable = false)
    private Instant detectedAt;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "acknowledged_by")
    private String acknowledgedBy;

    @Column(name = "mitigated_at")
    private Instant mitigatedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by")
    private String resolvedBy;

    @Column(name = "escalation_level", nullable = false)
    private int escalationLevel;

    @Column(name = "escalation_policy_key")
    private String escalationPolicyKey;

    @Column(name = "signal_count", nullable = false)
    private int signalCount;

    /** Set once an on-call engineer marks the incident as a duplicate of another. */
    @Column(name = "duplicate_of")
    private String duplicateOf;

    @OneToMany(mappedBy = "incident", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("occurredAt ASC")
    private List<IncidentSignal> signals = new ArrayList<>();

    protected Incident() {}

    public Incident(
            String tenantId,
            String incidentKey,
            String title,
            Severity severity,
            String primaryServiceKey,
            Instant detectedAt) {
        this.tenantId = tenantId;
        this.incidentKey = incidentKey;
        this.title = title;
        this.severity = severity;
        this.primaryServiceKey = primaryServiceKey;
        this.detectedAt = detectedAt;
        this.affectedServiceKeys = primaryServiceKey;
    }

    // ---------------------------------------------------------------- lifecycle

    public void acknowledge(String actor, Instant at) {
        requireTransitionTo(IncidentStatus.ACKNOWLEDGED);
        this.status = IncidentStatus.ACKNOWLEDGED;
        this.acknowledgedAt = at;
        this.acknowledgedBy = actor;
    }

    public void mitigate(Instant at) {
        requireTransitionTo(IncidentStatus.MITIGATED);
        this.status = IncidentStatus.MITIGATED;
        this.mitigatedAt = at;
    }

    public void resolve(String actor, Instant at) {
        requireTransitionTo(IncidentStatus.RESOLVED);
        this.status = IncidentStatus.RESOLVED;
        this.resolvedAt = at;
        this.resolvedBy = actor;
        // An incident resolved without an explicit ack was still, in effect, acknowledged.
        if (acknowledgedAt == null) {
            this.acknowledgedAt = at;
            this.acknowledgedBy = actor;
        }
    }

    public void escalate() {
        if (status.isTerminal()) {
            throw new InvalidStateTransitionException(status.name(), "ESCALATED");
        }
        this.escalationLevel++;
    }

    private void requireTransitionTo(IncidentStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStateTransitionException(status.name(), target.name());
        }
    }

    // ---------------------------------------------------------------- correlation

    /** Returns true when the incident's severity was actually raised, so callers can emit an event. */
    public boolean raiseSeverityTo(Severity candidate) {
        if (candidate == null || !candidate.isAtLeast(severity) || candidate == severity) {
            return false;
        }
        this.severity = candidate;
        return true;
    }

    public void attach(IncidentSignal signal) {
        signals.add(signal);
        signal.assignTo(this);
        this.signalCount++;
        addAffectedService(signal.getServiceKey());
    }

    public void addAffectedService(String serviceKey) {
        Set<String> keys = affectedServiceKeySet();
        if (keys.add(serviceKey)) {
            this.affectedServiceKeys = String.join(",", keys);
        }
    }

    public Set<String> affectedServiceKeySet() {
        Set<String> keys = new LinkedHashSet<>();
        if (!affectedServiceKeys.isBlank()) {
            keys.addAll(List.of(affectedServiceKeys.split(",")));
        }
        return keys;
    }

    // ---------------------------------------------------------------- metrics

    /** Time to acknowledge — the first half of MTTR, and the number on-call rotations are judged by. */
    public Duration timeToAcknowledge() {
        return acknowledgedAt == null ? null : Duration.between(detectedAt, acknowledgedAt);
    }

    public Duration timeToResolve() {
        return resolvedAt == null ? null : Duration.between(detectedAt, resolvedAt);
    }

    public boolean isAwaitingAcknowledgement() {
        return status == IncidentStatus.OPEN && acknowledgedAt == null;
    }

    // ---------------------------------------------------------------- accessors

    public String getTenantId() {
        return tenantId;
    }

    public String getIncidentKey() {
        return incidentKey;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public IncidentStatus getStatus() {
        return status;
    }

    public Severity getSeverity() {
        return severity;
    }

    public String getPrimaryServiceKey() {
        return primaryServiceKey;
    }

    public Instant getDetectedAt() {
        return detectedAt;
    }

    public Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public String getAcknowledgedBy() {
        return acknowledgedBy;
    }

    public Instant getMitigatedAt() {
        return mitigatedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public int getEscalationLevel() {
        return escalationLevel;
    }

    public String getEscalationPolicyKey() {
        return escalationPolicyKey;
    }

    public void setEscalationPolicyKey(String escalationPolicyKey) {
        this.escalationPolicyKey = escalationPolicyKey;
    }

    public int getSignalCount() {
        return signalCount;
    }

    public String getDuplicateOf() {
        return duplicateOf;
    }

    public void markDuplicateOf(String incidentKey) {
        this.duplicateOf = incidentKey;
    }

    public List<IncidentSignal> getSignals() {
        return signals;
    }
}
