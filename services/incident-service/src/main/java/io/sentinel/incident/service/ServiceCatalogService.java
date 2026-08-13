package io.sentinel.incident.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.sentinel.platform.common.error.DomainException;
import io.sentinel.platform.domain.model.ServiceDependency;
import io.sentinel.platform.domain.model.ServiceNode;
import io.sentinel.platform.domain.repository.ServiceDependencyRepository;
import io.sentinel.platform.domain.repository.ServiceNodeRepository;

/**
 * Owns the service catalog and dependency edges.
 *
 * <p>Writes publish an invalidation on Redis so the correlation service drops its cached graph
 * immediately. Without it, an SRE could add the edge that explains an ongoing cascade and watch the
 * platform ignore it for the length of a cache TTL.
 */
@Service
public class ServiceCatalogService {

    private static final Logger log = LoggerFactory.getLogger(ServiceCatalogService.class);
    private static final String INVALIDATION_CHANNEL = "sentinel:graph:invalidate";

    private final ServiceNodeRepository serviceNodeRepository;
    private final ServiceDependencyRepository dependencyRepository;
    private final StringRedisTemplate redis;

    public ServiceCatalogService(
            ServiceNodeRepository serviceNodeRepository,
            ServiceDependencyRepository dependencyRepository,
            StringRedisTemplate redis) {
        this.serviceNodeRepository = serviceNodeRepository;
        this.dependencyRepository = dependencyRepository;
        this.redis = redis;
    }

    @Transactional(readOnly = true)
    public Topology topology(String tenantId) {
        List<ServiceNode> nodes = serviceNodeRepository.findByTenantIdOrderByServiceKeyAsc(tenantId);
        List<ServiceDependency> edges = dependencyRepository.findByTenantId(tenantId);

        Map<String, Long> inboundCounts =
                edges.stream().collect(Collectors.groupingBy(ServiceDependency::getTargetKey, Collectors.counting()));

        List<Node> nodeViews = nodes.stream()
                .map(node -> new Node(
                        node.getServiceKey(),
                        node.getDisplayName(),
                        node.getTier().name(),
                        node.getOwnerTeam(),
                        node.getRunbookUrl(),
                        inboundCounts.getOrDefault(node.getServiceKey(), 0L).intValue()))
                .toList();

        List<Edge> edgeViews = edges.stream()
                .map(edge -> new Edge(
                        edge.getSourceKey(), edge.getTargetKey(), edge.getKind().name(), edge.getCriticality()))
                .toList();

        return new Topology(nodeViews, edgeViews);
    }

    @Transactional
    public ServiceNode register(
            String tenantId, String serviceKey, String displayName, ServiceNode.Tier tier, String ownerTeam) {
        if (serviceNodeRepository.existsByTenantIdAndServiceKey(tenantId, serviceKey)) {
            throw new AlreadyRegisteredException(serviceKey);
        }
        ServiceNode node = new ServiceNode(tenantId, serviceKey, displayName, tier);
        node.setOwnerTeam(ownerTeam);

        ServiceNode saved = serviceNodeRepository.save(node);
        invalidateGraph(tenantId);
        return saved;
    }

    @Transactional
    public ServiceDependency addDependency(
            String tenantId, String sourceKey, String targetKey, ServiceDependency.Kind kind, Double criticality) {

        if (sourceKey.equals(targetKey)) {
            throw new InvalidDependencyException("A service cannot depend on itself");
        }
        if (dependencyRepository.existsByTenantIdAndSourceKeyAndTargetKey(tenantId, sourceKey, targetKey)) {
            throw new InvalidDependencyException("That dependency already exists");
        }

        ServiceDependency dependency = new ServiceDependency(tenantId, sourceKey, targetKey, kind);
        if (criticality != null) {
            dependency.setCriticality(criticality);
        }

        ServiceDependency saved = dependencyRepository.save(dependency);
        invalidateGraph(tenantId);
        log.info("Added {} dependency {} -> {}", kind, sourceKey, targetKey);
        return saved;
    }

    private void invalidateGraph(String tenantId) {
        try {
            redis.convertAndSend(INVALIDATION_CHANNEL, tenantId);
        } catch (RuntimeException e) {
            // Worst case the correlation service uses a slightly stale graph until its TTL lapses.
            log.warn("Could not publish graph invalidation for {}: {}", tenantId, e.getMessage());
        }
    }

    public record Topology(List<Node> nodes, List<Edge> edges) {}

    public record Node(
            String serviceKey,
            String displayName,
            String tier,
            String ownerTeam,
            String runbookUrl,
            int dependentCount) {}

    public record Edge(String source, String target, String kind, double criticality) {}

    public static class AlreadyRegisteredException extends DomainException {
        public AlreadyRegisteredException(String serviceKey) {
            super(
                    HttpStatus.CONFLICT,
                    "service_already_registered",
                    "%s is already in the catalog".formatted(serviceKey));
        }
    }

    public static class InvalidDependencyException extends DomainException {
        public InvalidDependencyException(String message) {
            super(HttpStatus.BAD_REQUEST, "invalid_dependency", message);
        }
    }
}
