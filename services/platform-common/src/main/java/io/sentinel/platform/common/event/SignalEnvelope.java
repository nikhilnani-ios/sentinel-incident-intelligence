package io.sentinel.platform.common.event;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Canonical wire format for everything that enters the platform.
 *
 * <p>Every producer — Alertmanager webhook, OTel collector, CI pipeline — is normalised into this
 * envelope at the edge, so nothing downstream of ingest has to know about vendor formats.
 *
 * @param eventId producer-supplied idempotency key; ingest rejects replays of the same id
 * @param serviceKey stable slug of the emitting service, e.g. {@code checkout-api}
 * @param occurredAt when the event happened at the source (may be well before we saw it)
 * @param receivedAt when ingest accepted it; set server-side, never trusted from the client
 */
public record SignalEnvelope(
        @NotBlank String eventId,
        @NotBlank String tenantId,
        @NotBlank String serviceKey,
        @NotNull Instant occurredAt,
        Instant receivedAt,
        Map<String, String> labels,
        @NotNull @Valid SignalPayload payload) {

    public SignalEnvelope {
        labels = labels == null ? Map.of() : Map.copyOf(labels);
    }

    public static SignalEnvelope of(String tenantId, String serviceKey, SignalPayload payload) {
        return new SignalEnvelope(
                UUID.randomUUID().toString(), tenantId, serviceKey, Instant.now(), Instant.now(), Map.of(), payload);
    }

    public SignalType type() {
        return payload.type();
    }

    public SignalEnvelope stampReceivedAt(Instant now) {
        return new SignalEnvelope(eventId, tenantId, serviceKey, occurredAt, now, labels, payload);
    }

    /**
     * Kafka partition key. Keying by tenant+service guarantees that all signals for one service
     * land on one partition, which is what makes single-threaded per-service correlation safe.
     */
    public String partitionKey() {
        return tenantId + ':' + serviceKey;
    }

    public Severity severity() {
        return switch (payload) {
            case AlertPayload alert -> alert.severity();
            case MetricPayload metric -> metric.breachesThreshold() ? Severity.HIGH : Severity.INFO;
            case LogPayload log -> log.isError() ? Severity.MEDIUM : Severity.INFO;
            case DeploymentPayload deploy -> deploy.status() == DeploymentPayload.Status.FAILED
                    ? Severity.MEDIUM
                    : Severity.INFO;
        };
    }
}
