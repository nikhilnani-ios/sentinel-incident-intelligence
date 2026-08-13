package io.sentinel.platform.common.event;

import jakarta.validation.constraints.NotBlank;

/**
 * A deployment or configuration rollout.
 *
 * <p>Deployments are the single highest-signal correlation input we have: most incidents are
 * change-induced, so the correlation engine weights a recent deploy heavily when ranking causes.
 */
public record DeploymentPayload(
        @NotBlank String version,
        String commitSha,
        String deployedBy,
        @NotBlank String environment,
        String changelogUrl,
        Status status)
        implements SignalPayload {

    public enum Status {
        STARTED,
        SUCCEEDED,
        FAILED,
        ROLLED_BACK
    }

    @Override
    public SignalType type() {
        return SignalType.DEPLOYMENT;
    }

    public boolean isCompleted() {
        return status == Status.SUCCEEDED || status == Status.FAILED || status == Status.ROLLED_BACK;
    }

    @Override
    public String summary() {
        return "Deploy %s to %s %s by %s"
                .formatted(version, environment, status, deployedBy == null ? "automation" : deployedBy);
    }
}
