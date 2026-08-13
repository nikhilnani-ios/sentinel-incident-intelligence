package io.sentinel.correlation.service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.sentinel.correlation.config.CorrelationProperties;
import io.sentinel.platform.common.event.SignalEnvelope;
import io.sentinel.platform.common.util.Fingerprints;

/**
 * Collapses repeat firings of the same problem.
 *
 * <p>This is the difference between an on-call engineer seeing one incident and seeing four hundred
 * pages from a crash-looping deployment. Every signal is reduced to a fingerprint (see
 * {@link Fingerprints}); the first occurrence within the dedup window opens the door, and repeats
 * are folded into the incident that fingerprint already belongs to.
 *
 * <p>Redis holds the window state rather than Postgres because it is written on every signal and
 * read on every signal, but is worthless after the window expires — exactly the shape of data that
 * should not be in a durable store. A Redis outage degrades us to "more duplicate incidents", never
 * to "dropped alerts".
 */
@Service
public class DeduplicationService {

    private static final Logger log = LoggerFactory.getLogger(DeduplicationService.class);
    private static final String KEY_PREFIX = "dedup:fp:";

    private final StringRedisTemplate redis;
    private final CorrelationProperties properties;
    private final Counter suppressedCounter;
    private final Counter firstSeenCounter;

    public DeduplicationService(
            StringRedisTemplate redis, CorrelationProperties properties, MeterRegistry meterRegistry) {
        this.redis = redis;
        this.properties = properties;
        this.suppressedCounter = Counter.builder("sentinel.correlation.dedup")
                .tag("outcome", "suppressed")
                .description("Signals folded into an existing incident by fingerprint")
                .register(meterRegistry);
        this.firstSeenCounter = Counter.builder("sentinel.correlation.dedup")
                .tag("outcome", "first_seen")
                .register(meterRegistry);
    }

    /**
     * Classifies a signal against the dedup window.
     *
     * @return the incident this fingerprint is already attached to, if any
     */
    public Verdict classify(SignalEnvelope signal) {
        String fingerprint = Fingerprints.of(signal);
        String key = KEY_PREFIX + fingerprint;
        Duration window = properties.dedupWindow();

        String existingIncidentId = redis.opsForValue().get(key);
        if (existingIncidentId != null) {
            // Sliding window: a continuously flapping alert stays suppressed rather than
            // resurfacing every time the original TTL happens to lapse.
            redis.expire(key, window);
            suppressedCounter.increment();
            log.debug("Suppressed repeat of fingerprint {} for {}", fingerprint, signal.serviceKey());
            return new Verdict(fingerprint, true, parseUuid(existingIncidentId));
        }

        firstSeenCounter.increment();
        return new Verdict(fingerprint, false, Optional.empty());
    }

    /**
     * Binds a fingerprint to the incident that now owns it, so the next occurrence inside the window
     * can be folded in without a database round trip.
     */
    public void remember(String fingerprint, UUID incidentId) {
        redis.opsForValue().set(KEY_PREFIX + fingerprint, incidentId.toString(), properties.dedupWindow());
    }

    /** Releases a fingerprint early — used when an incident resolves, so a recurrence pages again. */
    public void forget(String fingerprint) {
        redis.delete(KEY_PREFIX + fingerprint);
    }

    private Optional<UUID> parseUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            log.warn("Discarding malformed incident id in dedup cache: {}", value);
            return Optional.empty();
        }
    }

    /**
     * @param suppressed true when this fingerprint already fired inside the window
     * @param incidentId the incident that owns the fingerprint, when known
     */
    public record Verdict(String fingerprint, boolean suppressed, Optional<UUID> incidentId) {}
}
