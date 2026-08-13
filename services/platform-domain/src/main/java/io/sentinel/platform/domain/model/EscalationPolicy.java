package io.sentinel.platform.domain.model;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import io.sentinel.platform.common.event.Severity;

/**
 * An ordered ladder of who to wake, and how long to wait before giving up on them.
 *
 * <p>Modelled as data rather than code so that changing the on-call ladder is a config change, not
 * a deploy — which matters at 3am.
 */
@Entity
@Table(name = "escalation_policy")
public class EscalationPolicy extends BaseEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private String tenantId;

    @Column(name = "policy_key", nullable = false, updatable = false)
    private String policyKey;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    /** Only incidents at or above this severity engage the policy. */
    @Enumerated(EnumType.STRING)
    @Column(name = "minimum_severity", nullable = false)
    private Severity minimumSeverity = Severity.HIGH;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "policy_id", nullable = false)
    @OrderBy("stepOrder ASC")
    private List<EscalationStep> steps = new ArrayList<>();

    protected EscalationPolicy() {}

    public EscalationPolicy(String tenantId, String policyKey, String displayName, Severity minimumSeverity) {
        this.tenantId = tenantId;
        this.policyKey = policyKey;
        this.displayName = displayName;
        this.minimumSeverity = minimumSeverity;
    }

    public void addStep(EscalationStep step) {
        step.setStepOrder(steps.size());
        steps.add(step);
    }

    public boolean appliesTo(Severity severity) {
        return severity.isAtLeast(minimumSeverity);
    }

    /** The step to run at the given level, or empty when the ladder is exhausted. */
    public Optional<EscalationStep> stepAt(int level) {
        return steps.stream().filter(step -> step.getStepOrder() == level).findFirst();
    }

    /** How long to wait after the previous notification before advancing to {@code level}. */
    public Duration delayBefore(int level) {
        return stepAt(level).map(EscalationStep::getDelay).orElse(Duration.ofMinutes(15));
    }

    public boolean isExhausted(int level) {
        return steps.stream().mapToInt(EscalationStep::getStepOrder).max().orElse(-1) < level;
    }

    public List<EscalationStep> getSteps() {
        return steps.stream()
                .sorted(Comparator.comparingInt(EscalationStep::getStepOrder))
                .toList();
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getPolicyKey() {
        return policyKey;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Severity getMinimumSeverity() {
        return minimumSeverity;
    }
}
