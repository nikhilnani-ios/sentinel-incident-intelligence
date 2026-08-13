package io.sentinel.platform.common.event;

import jakarta.validation.constraints.NotBlank;

/** A single metric observation, optionally carrying the threshold it breached. */
public record MetricPayload(
        @NotBlank String metricName, double value, String unit, Double threshold, Comparison comparison)
        implements SignalPayload {

    public enum Comparison {
        ABOVE,
        BELOW
    }

    @Override
    public SignalType type() {
        return SignalType.METRIC;
    }

    public boolean breachesThreshold() {
        if (threshold == null || comparison == null) {
            return false;
        }
        return comparison == Comparison.ABOVE ? value > threshold : value < threshold;
    }

    @Override
    public String summary() {
        String reading = "%s=%.3f%s".formatted(metricName, value, unit == null ? "" : unit);
        return breachesThreshold()
                ? "%s breached threshold %.3f (%s)".formatted(reading, threshold, comparison)
                : reading;
    }
}
