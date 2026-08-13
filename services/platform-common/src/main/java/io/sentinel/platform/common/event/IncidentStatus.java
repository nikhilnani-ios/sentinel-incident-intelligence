package io.sentinel.platform.common.event;

/** Lifecycle of an incident. Transitions are enforced by {@code IncidentCommandService}. */
public enum IncidentStatus {
    OPEN,
    ACKNOWLEDGED,
    MITIGATED,
    RESOLVED;

    public boolean isTerminal() {
        return this == RESOLVED;
    }

    public boolean isOpen() {
        return !isTerminal();
    }

    public boolean canTransitionTo(IncidentStatus target) {
        if (this == target) {
            return false;
        }
        return switch (this) {
            case OPEN -> target != OPEN;
            case ACKNOWLEDGED -> target == MITIGATED || target == RESOLVED;
            case MITIGATED -> target == RESOLVED || target == ACKNOWLEDGED;
            case RESOLVED -> false;
        };
    }
}
