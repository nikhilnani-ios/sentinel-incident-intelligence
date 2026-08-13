package io.sentinel.platform.domain.model;

import java.time.Duration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/** One rung of an escalation ladder. */
@Entity
@Table(name = "escalation_step")
public class EscalationStep extends BaseEntity {

    @Column(name = "step_order", nullable = false)
    private int stepOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false)
    private TargetType targetType;

    /** Team name, user id, or webhook url depending on {@link #targetType}. */
    @Column(name = "target", nullable = false)
    private String target;

    @Column(name = "delay_seconds", nullable = false)
    private long delaySeconds;

    public enum TargetType {
        USER,
        TEAM,
        WEBHOOK
    }

    protected EscalationStep() {}

    public EscalationStep(TargetType targetType, String target, Duration delay) {
        this.targetType = targetType;
        this.target = target;
        this.delaySeconds = delay.toSeconds();
    }

    void setStepOrder(int stepOrder) {
        this.stepOrder = stepOrder;
    }

    public int getStepOrder() {
        return stepOrder;
    }

    public TargetType getTargetType() {
        return targetType;
    }

    public String getTarget() {
        return target;
    }

    public Duration getDelay() {
        return Duration.ofSeconds(delaySeconds);
    }
}
