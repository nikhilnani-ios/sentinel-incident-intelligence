package io.sentinel.correlation.graph;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.sentinel.platform.domain.repository.ServiceDependencyRepository;

/**
 * Caches the per-tenant {@link ServiceGraph} in process.
 *
 * <p>The graph is read on every single signal and written a handful of times a day, so the cache
 * is local rather than in Redis: a topology of a few thousand edges is well under a megabyte, and a
 * local read costs nanoseconds instead of a network hop on the hot path.
 *
 * <p>Staleness is bounded two ways — a TTL as the backstop, and an explicit invalidation published
 * over Redis whenever the catalog changes, so an edge edit is visible everywhere within a second
 * instead of at the end of the TTL.
 */
@Component
public class ServiceGraphProvider {

    private static final Logger log = LoggerFactory.getLogger(ServiceGraphProvider.class);

    private final ServiceDependencyRepository dependencyRepository;
    private final Map<String, CachedGraph> cache = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Timer loadTimer;

    public ServiceGraphProvider(
            ServiceDependencyRepository dependencyRepository,
            MeterRegistry meterRegistry,
            @Value("${sentinel.correlation.graph-cache-ttl:PT5M}") Duration ttl) {
        this.dependencyRepository = dependencyRepository;
        this.ttl = ttl;
        this.loadTimer = Timer.builder("sentinel.graph.load")
                .description("Time spent rebuilding a tenant service graph from the database")
                .register(meterRegistry);
    }

    public ServiceGraph forTenant(String tenantId) {
        CachedGraph cached = cache.get(tenantId);
        if (cached != null && !cached.isExpired(ttl)) {
            return cached.graph();
        }
        return cache.compute(
                        tenantId,
                        (key, existing) -> existing != null && !existing.isExpired(ttl) ? existing : load(key))
                .graph();
    }

    public void invalidate(String tenantId) {
        if (cache.remove(tenantId) != null) {
            log.info("Invalidated cached service graph for tenant {}", tenantId);
        }
    }

    private CachedGraph load(String tenantId) {
        return loadTimer.record(() -> {
            var edges = dependencyRepository.findByTenantId(tenantId);
            log.debug("Loaded {} dependency edges for tenant {}", edges.size(), tenantId);
            return new CachedGraph(ServiceGraph.from(edges), Instant.now());
        });
    }

    private record CachedGraph(ServiceGraph graph, Instant loadedAt) {
        boolean isExpired(Duration ttl) {
            return loadedAt.plus(ttl).isBefore(Instant.now());
        }
    }
}
