package io.sentinel.platform.domain.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import io.sentinel.platform.common.event.DeploymentPayload;

/** A recorded rollout, kept so that incidents can be correlated back to the change that caused them. */
@Entity
@Table(name = "deployment")
public class Deployment extends BaseEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private String tenantId;

    @Column(name = "service_key", nullable = false)
    private String serviceKey;

    /** Column is {@code version_label}: {@code version} is taken by optimistic locking on the base class. */
    @Column(name = "version_label", nullable = false)
    private String versionLabel;

    @Column(name = "commit_sha")
    private String commitSha;

    @Column(name = "environment", nullable = false)
    private String environment;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private DeploymentPayload.Status status;

    @Column(name = "deployed_by")
    private String deployedBy;

    @Column(name = "changelog_url")
    private String changelogUrl;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected Deployment() {}

    public Deployment(
            String tenantId,
            String serviceKey,
            String version,
            String environment,
            DeploymentPayload.Status status,
            Instant occurredAt) {
        this.tenantId = tenantId;
        this.serviceKey = serviceKey;
        this.versionLabel = version;
        this.environment = environment;
        this.status = status;
        this.occurredAt = occurredAt;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getServiceKey() {
        return serviceKey;
    }

    public String getVersionLabel() {
        return versionLabel;
    }

    public String getCommitSha() {
        return commitSha;
    }

    public void setCommitSha(String commitSha) {
        this.commitSha = commitSha;
    }

    public String getEnvironment() {
        return environment;
    }

    public DeploymentPayload.Status getStatus() {
        return status;
    }

    public String getDeployedBy() {
        return deployedBy;
    }

    public void setDeployedBy(String deployedBy) {
        this.deployedBy = deployedBy;
    }

    public String getChangelogUrl() {
        return changelogUrl;
    }

    public void setChangelogUrl(String changelogUrl) {
        this.changelogUrl = changelogUrl;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
