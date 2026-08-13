package io.sentinel.ingest.service;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Per-tenant token bucket, evaluated inside Redis.
 *
 * <p>The read-modify-write has to be atomic across replicas, so it runs as a Lua script: Redis
 * executes it single-threaded, which gives us compare-and-set semantics without a distributed lock.
 *
 * <p>A noisy tenant flooding logs must not be able to delay another tenant's CRITICAL alert, which
 * is why the bucket key includes the tenant and the limit is enforced before anything touches Kafka.
 */
@Component
public class IngestRateLimiter {

    private static final String BUCKET_SCRIPT =
            """
            local key      = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill   = tonumber(ARGV[2])
            local now      = tonumber(ARGV[3])
            local cost     = tonumber(ARGV[4])

            local bucket = redis.call('HMGET', key, 'tokens', 'timestamp')
            local tokens = tonumber(bucket[1])
            local last   = tonumber(bucket[2])

            if tokens == nil then
              tokens = capacity
              last = now
            end

            local elapsed = math.max(0, now - last)
            tokens = math.min(capacity, tokens + (elapsed * refill))

            local allowed = 0
            if tokens >= cost then
              tokens = tokens - cost
              allowed = 1
            end

            redis.call('HMSET', key, 'tokens', tokens, 'timestamp', now)
            redis.call('EXPIRE', key, ARGV[5])
            return { allowed, math.floor(tokens) }
            """;

    private final StringRedisTemplate redis;
    private final RedisScript<List> script;
    private final int capacity;
    private final double refillPerSecond;

    public IngestRateLimiter(
            StringRedisTemplate redis,
            @Value("${sentinel.ingest.rate-limit.capacity:5000}") int capacity,
            @Value("${sentinel.ingest.rate-limit.refill-per-second:500}") double refillPerSecond) {
        this.redis = redis;
        this.script = new DefaultRedisScript<>(BUCKET_SCRIPT, List.class);
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
    }

    @SuppressWarnings("unchecked")
    public Decision tryConsume(String tenantId, int cost) {
        String key = "ingest:ratelimit:" + tenantId;
        long ttlSeconds = Duration.ofMinutes(10).toSeconds();

        List<Long> result;
        try {
            result = (List<Long>) redis.execute(
                    script,
                    List.of(key),
                    String.valueOf(capacity),
                    String.valueOf(refillPerSecond),
                    String.valueOf(System.currentTimeMillis() / 1000.0),
                    String.valueOf(cost),
                    String.valueOf(ttlSeconds));
        } catch (RuntimeException e) {
            // Fail open. Losing an alert because the rate limiter is down is worse than
            // briefly serving an over-quota tenant.
            return new Decision(true, capacity);
        }

        if (result == null || result.size() < 2) {
            return new Decision(true, capacity);
        }
        return new Decision(result.get(0) == 1L, result.get(1).intValue());
    }

    public record Decision(boolean allowed, int remainingTokens) {}
}
