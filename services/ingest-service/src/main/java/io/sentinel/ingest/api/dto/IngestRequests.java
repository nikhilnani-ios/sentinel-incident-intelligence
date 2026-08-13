package io.sentinel.ingest.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.sentinel.platform.common.event.AlertPayload;
import io.sentinel.platform.common.event.DeploymentPayload;
import io.sentinel.platform.common.event.LogPayload;
import io.sentinel.platform.common.event.MetricPayload;
import io.sentinel.platform.common.event.SignalEnvelope;
import io.sentinel.platform.common.event.SignalPayload;

/**
 * Wire contracts for the ingest API.
 *
 * <p>The DTOs deliberately mirror the internal payloads rather than reusing them: the public API
 * must be able to evolve on a different schedule from the Kafka schema, and clients should never be
 * able to set server-owned fields like {@code receivedAt} or {@code tenantId}.
 */
public final class IngestRequests {

    private IngestRequests() {}

    /** Fields shared by every ingest request. */
    public interface SignalRequest {
        String eventId();

        String serviceKey();

        Instant occurredAt();

        Map<String, String> labels();

        SignalPayload toPayload();

        default SignalEnvelope toEnvelope(String tenantId, Instant receivedAt) {
            return new SignalEnvelope(
                    eventId() == null || eventId().isBlank() ? UUID.randomUUID().toString() : eventId(),
                    tenantId,
                    serviceKey(),
                    occurredAt() == null ? receivedAt : occurredAt(),
                    receivedAt,
                    labels(),
                    toPayload());
        }
    }

    public record AlertRequest(
            String eventId,
            @NotBlank String serviceKey,
            Instant occurredAt,
            Map<String, String> labels,
            @NotBlank @Size(max = 200) String alertName,
            @NotNull io.sentinel.platform.common.event.Severity severity,
            @Size(max = 2000) String description,
            String runbookUrl,
            @NotBlank String source,
            boolean resolved,
            Map<String, String> annotations)
            implements SignalRequest {

        @Override
        public SignalPayload toPayload() {
            return new AlertPayload(alertName, severity, description, runbookUrl, source, resolved, annotations);
        }
    }

    public record MetricRequest(
            String eventId,
            @NotBlank String serviceKey,
            Instant occurredAt,
            Map<String, String> labels,
            @NotBlank String metricName,
            double value,
            String unit,
            Double threshold,
            MetricPayload.Comparison comparison)
            implements SignalRequest {

        @Override
        public SignalPayload toPayload() {
            return new MetricPayload(metricName, value, unit, threshold, comparison);
        }
    }

    public record LogRequest(
            String eventId,
            @NotBlank String serviceKey,
            Instant occurredAt,
            Map<String, String> labels,
            @NotBlank String level,
            @NotBlank @Size(max = 8000) String message,
            String loggerName,
            String traceId,
            @Size(max = 20000) String stackTrace,
            int occurrences)
            implements SignalRequest {

        @Override
        public SignalPayload toPayload() {
            return new LogPayload(level, message, loggerName, traceId, stackTrace, occurrences);
        }
    }

    public record DeploymentRequest(
            String eventId,
            @NotBlank String serviceKey,
            Instant occurredAt,
            Map<String, String> labels,
            @NotBlank String version,
            String commitSha,
            String deployedBy,
            @NotBlank String environment,
            String changelogUrl,
            @NotNull DeploymentPayload.Status status)
            implements SignalRequest {

        @Override
        public SignalPayload toPayload() {
            return new DeploymentPayload(version, commitSha, deployedBy, environment, changelogUrl, status);
        }
    }

    /** Batch wrapper. Collectors ship in batches; per-item results let one bad row fail alone. */
    public record BatchRequest<T>(@NotNull @Valid @Size(min = 1, max = 500) List<T> items) {}
}
