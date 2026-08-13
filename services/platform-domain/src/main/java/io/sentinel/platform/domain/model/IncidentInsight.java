package io.sentinel.platform.domain.model;

import java.util.List;
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
 * LLM-generated output for an incident.
 *
 * <p>Persisted with the model name, the context hash and the token counts. The context hash lets us
 * skip regeneration when nothing new has arrived, which is the difference between a demo and
 * something you can afford to run.
 */
@Entity
@Table(name = "incident_insight")
public class IncidentInsight extends BaseEntity {

    @Column(name = "incident_id", nullable = false, updatable = false)
    private UUID incidentId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    private Kind kind;

    @Column(name = "model", nullable = false)
    private String model;

    /** Digest of the context bundle this was generated from; regeneration is skipped if unchanged. */
    @Column(name = "context_hash", nullable = false)
    private String contextHash;

    @Column(name = "headline", nullable = false, length = 500)
    private String headline;

    @Column(name = "body", nullable = false, length = 20000)
    private String body;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "hypotheses", columnDefinition = "jsonb")
    private List<Map<String, Object>> hypotheses = List.of();

    @Column(name = "confidence", nullable = false)
    private double confidence;

    @Column(name = "input_tokens", nullable = false)
    private int inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private int outputTokens;

    public enum Kind {
        ROOT_CAUSE_ANALYSIS,
        POSTMORTEM
    }

    protected IncidentInsight() {}

    public IncidentInsight(UUID incidentId, String tenantId, Kind kind, String model, String contextHash) {
        this.incidentId = incidentId;
        this.tenantId = tenantId;
        this.kind = kind;
        this.model = model;
        this.contextHash = contextHash;
    }

    public IncidentInsight withContent(String headline, String body, double confidence) {
        this.headline = headline;
        this.body = body;
        this.confidence = Math.clamp(confidence, 0.0, 1.0);
        return this;
    }

    public IncidentInsight withHypotheses(List<Map<String, Object>> hypotheses) {
        this.hypotheses = hypotheses == null ? List.of() : hypotheses;
        return this;
    }

    public IncidentInsight withUsage(int inputTokens, int outputTokens) {
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        return this;
    }

    public UUID getIncidentId() {
        return incidentId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public Kind getKind() {
        return kind;
    }

    public String getModel() {
        return model;
    }

    public String getContextHash() {
        return contextHash;
    }

    public String getHeadline() {
        return headline;
    }

    public String getBody() {
        return body;
    }

    public List<Map<String, Object>> getHypotheses() {
        return hypotheses;
    }

    public double getConfidence() {
        return confidence;
    }

    public int getInputTokens() {
        return inputTokens;
    }

    public int getOutputTokens() {
        return outputTokens;
    }
}
