package io.sentinel.platform.common.event;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** A firing (or resolving) alert from a monitoring system such as Prometheus Alertmanager. */
public record AlertPayload(
        @NotBlank String alertName,
        @NotNull Severity severity,
        String description,
        String runbookUrl,
        @NotBlank String source,
        boolean resolved,
        Map<String, String> annotations)
        implements SignalPayload {

    public AlertPayload {
        annotations = annotations == null ? Map.of() : Map.copyOf(annotations);
    }

    @Override
    public SignalType type() {
        return SignalType.ALERT;
    }

    @Override
    public String summary() {
        return "%s alert '%s' %s (%s)".formatted(severity, alertName, resolved ? "resolved" : "firing", source);
    }
}
