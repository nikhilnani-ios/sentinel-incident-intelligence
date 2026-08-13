package io.sentinel.platform.domain.model;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Link between an incident and a deployment the engine considers suspicious, with the score that
 * justified the link.
 */
@Entity
@Table(name = "incident_deployment")
public class IncidentDeployment extends BaseEntity {

    @Column(name = "incident_id", nullable = false, updatable = false)
    private UUID incidentId;

    @Column(name = "deployment_id", nullable = false, updatable = false)
    private UUID deploymentId;

    /** 0.0–1.0: how likely this deploy caused the incident, given timing and graph distance. */
    @Column(name = "suspicion_score", nullable = false)
    private double suspicionScore;

    @Column(name = "rationale", nullable = false, length = 500)
    private String rationale;

    protected IncidentDeployment() {}

    public IncidentDeployment(UUID incidentId, UUID deploymentId, double suspicionScore, String rationale) {
        this.incidentId = incidentId;
        this.deploymentId = deploymentId;
        this.suspicionScore = suspicionScore;
        this.rationale = rationale;
    }

    public UUID getIncidentId() {
        return incidentId;
    }

    public UUID getDeploymentId() {
        return deploymentId;
    }

    public double getSuspicionScore() {
        return suspicionScore;
    }

    public String getRationale() {
        return rationale;
    }
}
