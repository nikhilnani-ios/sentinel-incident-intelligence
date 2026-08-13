package io.sentinel.ingest.service;

import java.time.Duration;
import java.util.Objects;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Rejects replays of an event id we have already accepted.
 *
 * <p>Backed by {@code SET key NX EX ttl}, which is atomic: two concurrent replicas racing on the
 * same event id cannot both win. The TTL bounds memory — we only need to remember an id for as long
 * as a client might realistically retry it.
 *
 * <p>Note this guards against <em>transport</em> duplicates (the same event delivered twice). Alert
 * <em>content</em> duplicates — the same problem firing repeatedly with new event ids — are a
 * separate concern handled by fingerprint deduplication in the correlation service.
 */
@Component
public class IdempotencyGuard {

    private static final String KEY_PREFIX = "ingest:seen:";

    private final StringRedisTemplate redis;
    private final Duration retentionWindow;

    public IdempotencyGuard(
            StringRedisTemplate redis,
            @org.springframework.beans.factory.annotation.Value("${sentinel.ingest.idempotency-window:PT6H}")
                    Duration retentionWindow) {
        this.redis = redis;
        this.retentionWindow = retentionWindow;
    }

    /** @return true when this is the first time we have seen the event id */
    public boolean claim(String tenantId, String eventId) {
        String key = KEY_PREFIX + tenantId + ':' + eventId;
        Boolean claimed = redis.opsForValue().setIfAbsent(key, "1", retentionWindow);
        return Boolean.TRUE.equals(claimed);
    }

    /**
     * Releases a claim so the event can be retried. Called when the Kafka publish fails: holding the
     * claim after a failed publish would silently drop the client's retry.
     */
    public void release(String tenantId, String eventId) {
        redis.delete(KEY_PREFIX + Objects.requireNonNull(tenantId) + ':' + eventId);
    }
}
