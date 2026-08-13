package io.sentinel.platform.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * A directed edge: {@code source} calls {@code target}.
 *
 * <p>Edges are stored flat rather than as an adjacency list on the node so they can be refreshed
 * independently by the OTel service-map importer without rewriting service rows.
 */
@Entity
@Table(name = "service_dependency")
public class ServiceDependency extends BaseEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private String tenantId;

    @Column(name = "source_key", nullable = false)
    private String sourceKey;

    @Column(name = "target_key", nullable = false)
    private String targetKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    private Kind kind = Kind.SYNC;

    /**
     * How badly the source degrades when the target is down, 0.0–1.0. Feeds the correlation score:
     * a hard synchronous dependency propagates failure far more reliably than a fire-and-forget
     * event publish.
     */
    @Column(name = "criticality", nullable = false)
    private double criticality = 0.5;

    public enum Kind {
        SYNC,
        ASYNC,
        DATASTORE,
        CACHE,
        THIRD_PARTY;

        /** Default propagation likelihood when nobody has tuned the edge by hand. */
        public double defaultCriticality() {
            return switch (this) {
                case SYNC, DATASTORE -> 0.9;
                case CACHE -> 0.6;
                case ASYNC -> 0.35;
                case THIRD_PARTY -> 0.5;
            };
        }
    }

    protected ServiceDependency() {}

    public ServiceDependency(String tenantId, String sourceKey, String targetKey, Kind kind) {
        this.tenantId = tenantId;
        this.sourceKey = sourceKey;
        this.targetKey = targetKey;
        this.kind = kind;
        this.criticality = kind.defaultCriticality();
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getSourceKey() {
        return sourceKey;
    }

    public String getTargetKey() {
        return targetKey;
    }

    public Kind getKind() {
        return kind;
    }

    public double getCriticality() {
        return criticality;
    }

    public void setCriticality(double criticality) {
        this.criticality = Math.clamp(criticality, 0.0, 1.0);
    }
}
