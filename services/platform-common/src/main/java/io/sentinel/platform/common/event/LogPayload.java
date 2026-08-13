package io.sentinel.platform.common.event;

import jakarta.validation.constraints.NotBlank;

/**
 * A log line, or an aggregated group of identical lines.
 *
 * <p>{@code occurrences} lets collectors pre-aggregate high-volume repeats so we ingest one event
 * for ten thousand identical stack traces instead of ten thousand events.
 */
public record LogPayload(
        @NotBlank String level,
        @NotBlank String message,
        String loggerName,
        String traceId,
        String stackTrace,
        int occurrences)
        implements SignalPayload {

    public LogPayload {
        occurrences = Math.max(occurrences, 1);
    }

    @Override
    public SignalType type() {
        return SignalType.LOG;
    }

    public boolean isError() {
        return "ERROR".equalsIgnoreCase(level) || "FATAL".equalsIgnoreCase(level);
    }

    @Override
    public String summary() {
        String head = message.length() > 180 ? message.substring(0, 180) + "..." : message;
        return occurrences > 1 ? "[%s x%d] %s".formatted(level, occurrences, head) : "[%s] %s".formatted(level, head);
    }
}
