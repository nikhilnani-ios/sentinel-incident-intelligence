package io.sentinel.incident.stream;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.sentinel.platform.common.event.IncidentEvent;

/**
 * Holds the open SSE connections and fans incident events out to them.
 *
 * <p>SSE rather than WebSockets because the traffic is strictly one-way — the server pushes,
 * the browser only ever reads. That buys automatic reconnection in every browser, plain HTTP
 * semantics through load balancers, and no protocol upgrade to configure.
 *
 * <p>Subscribers are keyed by tenant so one tenant's events can never reach another's browser, and
 * emitters are stored in a {@link CopyOnWriteArrayList}: reads (every event) vastly outnumber
 * writes (connect and disconnect).
 *
 * <p>A heartbeat comment is sent periodically. Proxies happily kill an idle connection after a
 * minute or two, and a silent incident feed is worse than no feed at all.
 */
@Component
public class IncidentStreamRegistry {

    private static final Logger log = LoggerFactory.getLogger(IncidentStreamRegistry.class);
    private static final Duration EMITTER_TIMEOUT = Duration.ofMinutes(30);
    private static final int RECENT_EVENT_MEMORY = 512;

    private final Map<String, List<Subscriber>> subscribersByTenant = new ConcurrentHashMap<>();
    private final Set<String> recentlyDelivered = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final AtomicInteger openConnections = new AtomicInteger();

    public IncidentStreamRegistry(MeterRegistry meterRegistry) {
        Gauge.builder("sentinel.stream.connections", openConnections, AtomicInteger::get)
                .description("Currently open SSE connections")
                .register(meterRegistry);
    }

    public SseEmitter subscribe(String tenantId, String userId, Set<String> serviceFilter) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT.toMillis());
        Subscriber subscriber = new Subscriber(UUID.randomUUID(), userId, emitter, serviceFilter);

        subscribersByTenant
                .computeIfAbsent(tenantId, key -> new CopyOnWriteArrayList<>())
                .add(subscriber);
        openConnections.incrementAndGet();

        emitter.onCompletion(() -> remove(tenantId, subscriber));
        emitter.onTimeout(() -> remove(tenantId, subscriber));
        emitter.onError(error -> remove(tenantId, subscriber));

        sendQuietly(
                subscriber, "connected", Map.of("subscriberId", subscriber.id().toString()));
        log.debug("Opened incident stream for {} ({} total)", userId, openConnections.get());
        return emitter;
    }

    /** Pushes an event to every subscriber of its tenant, skipping ones filtered out by service. */
    public void push(IncidentEvent event) {
        if (!markDelivered(event)) {
            return;
        }

        List<Subscriber> subscribers = subscribersByTenant.get(event.tenantId());
        if (subscribers == null || subscribers.isEmpty()) {
            return;
        }

        for (Subscriber subscriber : subscribers) {
            if (subscriber.wants(event)) {
                sendQuietly(subscriber, "incident", event);
            }
        }
    }

    /**
     * Suppresses the echo of an event this replica both handled locally and then consumed back off
     * Kafka. The identity is change plus timestamp, which is unique per emission.
     */
    private boolean markDelivered(IncidentEvent event) {
        String key = event.incidentId() + ":" + event.change() + ":"
                + event.occurredAt().toEpochMilli();
        if (!recentlyDelivered.add(key)) {
            return false;
        }
        if (recentlyDelivered.size() > RECENT_EVENT_MEMORY) {
            recentlyDelivered.clear();
        }
        return true;
    }

    @Scheduled(fixedDelayString = "${sentinel.stream.heartbeat-interval-ms:20000}")
    public void heartbeat() {
        subscribersByTenant.forEach((tenantId, subscribers) -> {
            for (Subscriber subscriber : subscribers) {
                try {
                    subscriber.emitter().send(SseEmitter.event().comment("keep-alive"));
                } catch (IOException | IllegalStateException e) {
                    remove(tenantId, subscriber);
                }
            }
        });
    }

    private void sendQuietly(Subscriber subscriber, String eventName, Object payload) {
        try {
            subscriber.emitter().send(SseEmitter.event().name(eventName).data(payload));
        } catch (IOException | IllegalStateException e) {
            // A disconnected browser is routine, not an error worth a stack trace.
            log.trace("Dropping subscriber {}: {}", subscriber.id(), e.getMessage());
            subscriber.emitter().completeWithError(e);
        }
    }

    private void remove(String tenantId, Subscriber subscriber) {
        List<Subscriber> subscribers = subscribersByTenant.get(tenantId);
        if (subscribers != null && subscribers.remove(subscriber)) {
            openConnections.decrementAndGet();
        }
    }

    public int openConnectionCount() {
        return openConnections.get();
    }

    private record Subscriber(UUID id, String userId, SseEmitter emitter, Set<String> serviceFilter) {

        boolean wants(IncidentEvent event) {
            if (serviceFilter.isEmpty()) {
                return true;
            }
            return serviceFilter.contains(event.primaryServiceKey())
                    || event.affectedServiceKeys().stream().anyMatch(serviceFilter::contains);
        }
    }
}
