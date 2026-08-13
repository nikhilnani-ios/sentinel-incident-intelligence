package io.sentinel.platform.common.error;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * RFC 7807-shaped error body.
 *
 * <p>{@code traceId} is the single most useful field in production: it lets support paste one value
 * into Grafana and land on the exact request.
 */
public record ApiError(
        String code,
        String message,
        int status,
        String path,
        String traceId,
        Instant timestamp,
        List<FieldViolation> violations,
        Map<String, Object> details) {

    public record FieldViolation(String field, String message) {}

    public static ApiError of(String code, String message, int status, String path, String traceId) {
        return new ApiError(code, message, status, path, traceId, Instant.now(), List.of(), Map.of());
    }

    public ApiError withViolations(List<FieldViolation> violations) {
        return new ApiError(code, message, status, path, traceId, timestamp, violations, details);
    }
}
