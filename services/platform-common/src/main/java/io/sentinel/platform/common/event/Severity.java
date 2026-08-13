package io.sentinel.platform.common.event;

/**
 * Signal and incident severity, ordered from most to least urgent.
 *
 * <p>The rank is deliberately explicit rather than relying on {@code ordinal()}: correlation and
 * escalation both compare severities, and {@link #isAtLeast(Severity)} keeps that comparison from
 * leaking ordinal arithmetic into business code.
 */
public enum Severity {
    CRITICAL(0),
    HIGH(1),
    MEDIUM(2),
    LOW(3),
    INFO(4);

    private final int rank;

    Severity(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    public boolean isAtLeast(Severity other) {
        return this.rank <= other.rank;
    }

    /** Returns whichever of the two severities is more urgent. */
    public Severity max(Severity other) {
        return other == null || this.rank <= other.rank ? this : other;
    }

    /** True when the severity should wake a human up rather than wait for business hours. */
    public boolean isPageable() {
        return isAtLeast(HIGH);
    }
}
