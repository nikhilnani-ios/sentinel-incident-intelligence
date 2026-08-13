package io.sentinel.platform.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * A service in the catalog — one vertex of the dependency graph.
 *
 * <p>Named {@code ServiceNode} rather than {@code Service} to avoid colliding with
 * {@code org.springframework.stereotype.Service} in every file that touches both.
 */
@Entity
@Table(name = "service_node")
public class ServiceNode extends BaseEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private String tenantId;

    /** Stable slug used as the correlation key on every incoming signal. */
    @Column(name = "service_key", nullable = false, updatable = false)
    private String serviceKey;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "tier", nullable = false)
    private Tier tier = Tier.TIER_3;

    @Column(name = "owner_team")
    private String ownerTeam;

    @Column(name = "repository_url")
    private String repositoryUrl;

    @Column(name = "runbook_url")
    private String runbookUrl;

    /** Customer-facing services get higher blast-radius weight during correlation. */
    public enum Tier {
        TIER_1,
        TIER_2,
        TIER_3;

        public double blastRadiusWeight() {
            return switch (this) {
                case TIER_1 -> 1.0;
                case TIER_2 -> 0.7;
                case TIER_3 -> 0.4;
            };
        }
    }

    protected ServiceNode() {}

    public ServiceNode(String tenantId, String serviceKey, String displayName, Tier tier) {
        this.tenantId = tenantId;
        this.serviceKey = serviceKey;
        this.displayName = displayName;
        this.tier = tier;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getServiceKey() {
        return serviceKey;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public Tier getTier() {
        return tier;
    }

    public void setTier(Tier tier) {
        this.tier = tier;
    }

    public String getOwnerTeam() {
        return ownerTeam;
    }

    public void setOwnerTeam(String ownerTeam) {
        this.ownerTeam = ownerTeam;
    }

    public String getRepositoryUrl() {
        return repositoryUrl;
    }

    public void setRepositoryUrl(String repositoryUrl) {
        this.repositoryUrl = repositoryUrl;
    }

    public String getRunbookUrl() {
        return runbookUrl;
    }

    public void setRunbookUrl(String runbookUrl) {
        this.runbookUrl = runbookUrl;
    }
}
