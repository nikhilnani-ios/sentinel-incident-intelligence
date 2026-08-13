package io.sentinel.ingest.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.sentinel.ingest.api.dto.IngestRequests.SignalRequest;
import io.sentinel.ingest.api.dto.IngestResponse;
import io.sentinel.platform.common.event.SignalEnvelope;
import io.sentinel.platform.common.kafka.Topics;

/**
 * The write path for every incoming signal.
 *
 * <p>Ordering matters here. Rate limit, then idempotency, then publish — cheapest rejection first,
 * so a flood costs us a Redis round trip rather than a Kafka produce. The idempotency claim is
 * released if the publish fails so the client's retry is not silently swallowed.
 *
 * <p>Publishes are issued asynchronously and joined at the end of the batch: for a 500-item batch
 * this turns 500 sequential round trips into one pipelined flush.
 */
@Service
public class SignalIngestService {

    private static final Logger log = LoggerFactory.getLogger(SignalIngestService.class);
    private static final long PUBLISH_TIMEOUT_SECONDS = 10;

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final IdempotencyGuard idempotencyGuard;
    private final IngestRateLimiter rateLimiter;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public SignalIngestService(
            KafkaTemplate<String, Object> kafkaTemplate,
            IdempotencyGuard idempotencyGuard,
            IngestRateLimiter rateLimiter,
            MeterRegistry meterRegistry,
            Clock clock) {
        this.kafkaTemplate = kafkaTemplate;
        this.idempotencyGuard = idempotencyGuard;
        this.rateLimiter = rateLimiter;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    public IngestResponse ingest(String tenantId, List<? extends SignalRequest> requests) {
        IngestRateLimiter.Decision decision = rateLimiter.tryConsume(tenantId, requests.size());
        if (!decision.allowed()) {
            counter("rejected", "rate_limited").increment(requests.size());
            throw new RateLimitExceededException(tenantId, decision.remainingTokens());
        }

        Instant receivedAt = clock.instant();
        List<IngestResponse.Item> results = new ArrayList<>(requests.size());
        List<PendingPublish> pending = new ArrayList<>(requests.size());

        for (SignalRequest request : requests) {
            SignalEnvelope envelope = request.toEnvelope(tenantId, receivedAt);

            if (!idempotencyGuard.claim(tenantId, envelope.eventId())) {
                results.add(IngestResponse.Item.duplicate(envelope.eventId()));
                counter("duplicate", envelope.type().name()).increment();
                continue;
            }

            String topic = Topics.forSignalType(envelope.type());
            pending.add(new PendingPublish(
                    envelope,
                    kafkaTemplate.send(topic, envelope.partitionKey(), envelope).toCompletableFuture()));
        }

        results.addAll(awaitAll(tenantId, pending));
        return IngestResponse.from(results);
    }

    private List<IngestResponse.Item> awaitAll(String tenantId, List<PendingPublish> pending) {
        List<IngestResponse.Item> items = new ArrayList<>(pending.size());
        for (PendingPublish publish : pending) {
            SignalEnvelope envelope = publish.envelope();
            try {
                publish.future().get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                items.add(IngestResponse.Item.accepted(envelope.eventId()));
                counter("accepted", envelope.type().name()).increment();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                items.add(release(tenantId, envelope, "Interrupted while publishing"));
            } catch (ExecutionException | TimeoutException e) {
                log.error("Failed to publish signal {} for {}", envelope.eventId(), envelope.serviceKey(), e);
                items.add(release(tenantId, envelope, "Broker unavailable, retry this event"));
            }
        }
        return items;
    }

    private IngestResponse.Item release(String tenantId, SignalEnvelope envelope, String reason) {
        idempotencyGuard.release(tenantId, envelope.eventId());
        counter("rejected", "publish_failed").increment();
        return IngestResponse.Item.rejected(envelope.eventId(), reason);
    }

    private Counter counter(String outcome, String detail) {
        return Counter.builder("sentinel.ingest.signals")
                .tag("outcome", outcome)
                .tag("detail", detail)
                .description("Signals processed by the ingest edge, by outcome")
                .register(meterRegistry);
    }

    private record PendingPublish(SignalEnvelope envelope, CompletableFuture<?> future) {}

    /** Thrown when a tenant exhausts its token bucket; surfaces as HTTP 429. */
    public static class RateLimitExceededException extends io.sentinel.platform.common.error.DomainException {
        public RateLimitExceededException(String tenantId, int remaining) {
            super(
                    org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                    "rate_limit_exceeded",
                    "Tenant %s exceeded its ingest quota (%d tokens left). Retry with backoff."
                            .formatted(tenantId, remaining));
        }
    }
}
