package io.sentinel.incident.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.sentinel.incident.api.dto.IncidentResponses;
import io.sentinel.platform.common.error.ResourceNotFoundException;
import io.sentinel.platform.common.event.IncidentStatus;
import io.sentinel.platform.common.event.Severity;
import io.sentinel.platform.domain.model.Deployment;
import io.sentinel.platform.domain.model.Incident;
import io.sentinel.platform.domain.model.IncidentInsight;
import io.sentinel.platform.domain.model.ServiceNode;
import io.sentinel.platform.domain.repository.DeploymentRepository;
import io.sentinel.platform.domain.repository.IncidentDeploymentRepository;
import io.sentinel.platform.domain.repository.IncidentInsightRepository;
import io.sentinel.platform.domain.repository.IncidentRepository;
import io.sentinel.platform.domain.repository.IncidentSignalRepository;
import io.sentinel.platform.domain.repository.ServiceDependencyRepository;
import io.sentinel.platform.domain.repository.ServiceNodeRepository;
import io.sentinel.platform.domain.repository.TimelineEntryRepository;

/**
 * Read side of the incident API.
 *
 * <p>Assembling the detail view takes several queries — signals, timeline, deployments, topology,
 * analysis. They are issued explicitly rather than left to lazy loading: five deliberate queries
 * beat an N+1 that only shows up under production load.
 */
@Service
@Transactional(readOnly = true)
public class IncidentQueryService {

    private final IncidentRepository incidentRepository;
    private final IncidentSignalRepository signalRepository;
    private final TimelineEntryRepository timelineRepository;
    private final IncidentDeploymentRepository incidentDeploymentRepository;
    private final DeploymentRepository deploymentRepository;
    private final IncidentInsightRepository insightRepository;
    private final ServiceNodeRepository serviceNodeRepository;
    private final ServiceDependencyRepository dependencyRepository;

    @SuppressWarnings("java:S107")
    public IncidentQueryService(
            IncidentRepository incidentRepository,
            IncidentSignalRepository signalRepository,
            TimelineEntryRepository timelineRepository,
            IncidentDeploymentRepository incidentDeploymentRepository,
            DeploymentRepository deploymentRepository,
            IncidentInsightRepository insightRepository,
            ServiceNodeRepository serviceNodeRepository,
            ServiceDependencyRepository dependencyRepository) {
        this.incidentRepository = incidentRepository;
        this.signalRepository = signalRepository;
        this.timelineRepository = timelineRepository;
        this.incidentDeploymentRepository = incidentDeploymentRepository;
        this.deploymentRepository = deploymentRepository;
        this.insightRepository = insightRepository;
        this.serviceNodeRepository = serviceNodeRepository;
        this.dependencyRepository = dependencyRepository;
    }

    public Page<IncidentResponses.Summary> search(
            String tenantId, IncidentStatus status, Severity severity, String serviceKey, Pageable pageable) {
        return incidentRepository
                .search(tenantId, status, severity, serviceKey, pageable)
                .map(IncidentResponses.Summary::from);
    }

    public IncidentResponses.Detail detail(String tenantId, UUID incidentId) {
        Incident incident = incidentRepository
                .findByTenantIdAndId(tenantId, incidentId)
                .orElseThrow(() -> new ResourceNotFoundException("Incident", incidentId));

        return new IncidentResponses.Detail(
                IncidentResponses.Summary.from(incident),
                incident.getSummary(),
                incident.getAcknowledgedBy(),
                incident.getResolvedBy(),
                incident.getEscalationPolicyKey(),
                signalRepository.findByIncidentIdOrderByOccurredAtAsc(incidentId).stream()
                        .map(IncidentResponses.Signal::from)
                        .toList(),
                timelineRepository.findByIncidentIdOrderByOccurredAtAsc(incidentId).stream()
                        .map(IncidentResponses.Timeline::from)
                        .toList(),
                suspectDeployments(incidentId),
                localTopology(incident),
                latestAnalysis(incidentId).orElse(null));
    }

    private List<IncidentResponses.SuspectDeployment> suspectDeployments(UUID incidentId) {
        var links = incidentDeploymentRepository.findByIncidentIdOrderBySuspicionScoreDesc(incidentId);
        if (links.isEmpty()) {
            return List.of();
        }

        Map<UUID, Deployment> deployments =
                deploymentRepository
                        .findAllById(links.stream()
                                .map(link -> link.getDeploymentId())
                                .toList())
                        .stream()
                        .collect(Collectors.toMap(Deployment::getId, Function.identity()));

        return links.stream()
                .map(link -> Optional.ofNullable(deployments.get(link.getDeploymentId()))
                        .map(deployment -> new IncidentResponses.SuspectDeployment(
                                deployment.getId(),
                                deployment.getServiceKey(),
                                deployment.getVersionLabel(),
                                deployment.getCommitSha(),
                                deployment.getEnvironment(),
                                deployment.getDeployedBy(),
                                deployment.getChangelogUrl(),
                                deployment.getOccurredAt(),
                                link.getSuspicionScore(),
                                link.getRationale()))
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * The slice of the topology worth drawing: the affected services and their immediate neighbours.
     * Rendering the entire catalog would bury the three nodes that matter.
     */
    private List<IncidentResponses.GraphNode> localTopology(Incident incident) {
        Set<String> affected = incident.affectedServiceKeySet();
        var edges = dependencyRepository.findByTenantId(incident.getTenantId());

        Set<String> visible = edges.stream()
                .filter(edge -> affected.contains(edge.getSourceKey()) || affected.contains(edge.getTargetKey()))
                .flatMap(edge -> java.util.stream.Stream.of(edge.getSourceKey(), edge.getTargetKey()))
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        visible.addAll(affected);

        Map<String, ServiceNode> nodes =
                serviceNodeRepository
                        .findByTenantIdAndServiceKeyIn(incident.getTenantId(), List.copyOf(visible))
                        .stream()
                        .collect(Collectors.toMap(ServiceNode::getServiceKey, Function.identity()));

        return visible.stream()
                .map(key -> {
                    ServiceNode node = nodes.get(key);
                    List<String> dependsOn = edges.stream()
                            .filter(edge -> edge.getSourceKey().equals(key) && visible.contains(edge.getTargetKey()))
                            .map(edge -> edge.getTargetKey())
                            .toList();

                    return new IncidentResponses.GraphNode(
                            key,
                            node == null ? key : node.getDisplayName(),
                            node == null ? "TIER_3" : node.getTier().name(),
                            affected.contains(key),
                            key.equals(incident.getPrimaryServiceKey()),
                            node == null ? 0.4 : node.getTier().blastRadiusWeight(),
                            dependsOn);
                })
                .toList();
    }

    private Optional<IncidentResponses.Insight> latestAnalysis(UUID incidentId) {
        return insightRepository
                .findFirstByIncidentIdAndKindOrderByCreatedAtDesc(incidentId, IncidentInsight.Kind.ROOT_CAUSE_ANALYSIS)
                .map(insight -> new IncidentResponses.Insight(
                        insight.getHeadline(),
                        insight.getBody(),
                        insight.getConfidence(),
                        insight.getModel(),
                        insight.getHypotheses(),
                        insight.getCreatedAt()));
    }
}
