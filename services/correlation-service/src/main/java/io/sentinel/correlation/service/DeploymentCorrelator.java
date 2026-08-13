package io.sentinel.correlation.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.sentinel.correlation.config.CorrelationProperties;
import io.sentinel.correlation.graph.ServiceGraph;
import io.sentinel.platform.common.event.DeploymentPayload;
import io.sentinel.platform.domain.model.Deployment;
import io.sentinel.platform.domain.model.Incident;
import io.sentinel.platform.domain.model.IncidentDeployment;
import io.sentinel.platform.domain.model.TimelineEntry;
import io.sentinel.platform.domain.repository.DeploymentRepository;
import io.sentinel.platform.domain.repository.IncidentDeploymentRepository;
import io.sentinel.platform.domain.repository.TimelineEntryRepository;

/**
 * Links incidents to the deploys that plausibly caused them.
 *
 * <p>Most outages are change-induced, so "what shipped just before this broke" is the single most
 * useful question the platform can answer automatically. Suspicion combines three things:
 *
 * <ul>
 *   <li><b>Recency</b> — a deploy four minutes before detection is far more suspicious than one
 *       fifty minutes before. Decays linearly across the lookback window.
 *   <li><b>Graph distance</b> — a deploy to the failing service itself outranks a deploy to
 *       something three hops upstream.
 *   <li><b>Outcome</b> — a failed or rolled-back deploy is more suspicious than a clean one.
 * </ul>
 *
 * <p>The score is presented, never acted on: the platform surfaces a ranked list and lets a human
 * decide. Automatic rollback on a correlation score is how you turn one outage into two.
 */
@Service
public class DeploymentCorrelator {

    private static final Logger log = LoggerFactory.getLogger(DeploymentCorrelator.class);
    private static final double MINIMUM_SUSPICION = 0.25;
    private static final int MAX_LINKED_DEPLOYMENTS = 5;

    private final DeploymentRepository deploymentRepository;
    private final IncidentDeploymentRepository incidentDeploymentRepository;
    private final TimelineEntryRepository timelineRepository;
    private final CorrelationProperties properties;

    public DeploymentCorrelator(
            DeploymentRepository deploymentRepository,
            IncidentDeploymentRepository incidentDeploymentRepository,
            TimelineEntryRepository timelineRepository,
            CorrelationProperties properties) {
        this.deploymentRepository = deploymentRepository;
        this.incidentDeploymentRepository = incidentDeploymentRepository;
        this.timelineRepository = timelineRepository;
        this.properties = properties;
    }

    /** Finds and links suspicious deploys for a freshly created incident. */
    public List<IncidentDeployment> linkSuspects(Incident incident, ServiceGraph graph) {
        Instant from = incident.getDetectedAt().minus(properties.deploymentLookback());
        List<String> searchScope = scopeFor(incident, graph);

        List<Deployment> candidates =
                deploymentRepository.findInWindow(incident.getTenantId(), searchScope, from, incident.getDetectedAt());

        List<IncidentDeployment> links = new ArrayList<>();
        for (Deployment deployment : candidates) {
            double suspicion = suspicionOf(deployment, incident, graph);
            if (suspicion < MINIMUM_SUSPICION
                    || incidentDeploymentRepository.existsByIncidentIdAndDeploymentId(
                            incident.getId(), deployment.getId())) {
                continue;
            }

            String rationale = rationaleFor(deployment, incident, suspicion);
            links.add(new IncidentDeployment(incident.getId(), deployment.getId(), suspicion, rationale));
        }

        links.sort((a, b) -> Double.compare(b.getSuspicionScore(), a.getSuspicionScore()));
        List<IncidentDeployment> topLinks =
                links.stream().limit(MAX_LINKED_DEPLOYMENTS).toList();

        if (!topLinks.isEmpty()) {
            incidentDeploymentRepository.saveAll(topLinks);
            recordTimeline(incident, candidates, topLinks);
            log.info(
                    "Linked {} suspicious deployments to {} (top score {})",
                    topLinks.size(),
                    incident.getIncidentKey(),
                    String.format("%.2f", topLinks.get(0).getSuspicionScore()));
        }
        return topLinks;
    }

    /** The failing service plus everything it depends on — a deploy upstream can break us downstream. */
    private List<String> scopeFor(Incident incident, ServiceGraph graph) {
        List<String> scope = new ArrayList<>(incident.affectedServiceKeySet());
        for (String affected : incident.affectedServiceKeySet()) {
            scope.addAll(
                    graph.dependenciesOf(affected, properties.maxGraphDepth()).keySet());
        }
        return scope.stream().distinct().toList();
    }

    private double suspicionOf(Deployment deployment, Incident incident, ServiceGraph graph) {
        double recency = recencyScore(deployment.getOccurredAt(), incident.getDetectedAt());
        double proximity = incident.affectedServiceKeySet().contains(deployment.getServiceKey())
                ? 1.0
                : incident.affectedServiceKeySet().stream()
                        .mapToDouble(affected ->
                                graph.relatedness(deployment.getServiceKey(), affected, properties.maxGraphDepth()))
                        .max()
                        .orElse(0.0);
        double outcome = outcomeMultiplier(deployment.getStatus());

        return Math.clamp(((recency * 0.5) + (proximity * 0.5)) * outcome, 0.0, 1.0);
    }

    private double recencyScore(Instant deployedAt, Instant detectedAt) {
        Duration gap = Duration.between(deployedAt, detectedAt);
        if (gap.isNegative()) {
            return 0.0;
        }
        double window = properties.deploymentLookback().toMillis();
        return Math.max(0.0, 1.0 - (gap.toMillis() / window));
    }

    private double outcomeMultiplier(DeploymentPayload.Status status) {
        return switch (status) {
            case FAILED, ROLLED_BACK -> 1.0;
            case SUCCEEDED -> 0.85;
                // A deploy still in flight during an outage is highly suspicious.
            case STARTED -> 0.95;
        };
    }

    private String rationaleFor(Deployment deployment, Incident incident, double suspicion) {
        long minutesBefore = Duration.between(deployment.getOccurredAt(), incident.getDetectedAt())
                .toMinutes();
        boolean direct = incident.affectedServiceKeySet().contains(deployment.getServiceKey());

        return "%s %s deployed to %s %d min before detection (%s), suspicion %.2f"
                .formatted(
                        deployment.getServiceKey(),
                        deployment.getVersionLabel(),
                        deployment.getEnvironment(),
                        minutesBefore,
                        direct ? "affected service" : "upstream dependency",
                        suspicion);
    }

    private void recordTimeline(Incident incident, List<Deployment> candidates, List<IncidentDeployment> links) {
        Map<java.util.UUID, Deployment> byId =
                candidates.stream().collect(java.util.stream.Collectors.toMap(Deployment::getId, d -> d));

        for (IncidentDeployment link : links) {
            Deployment deployment = byId.get(link.getDeploymentId());
            if (deployment == null) {
                continue;
            }
            timelineRepository.save(new TimelineEntry(
                            incident.getId(),
                            TimelineEntry.Kind.DEPLOYMENT_LINKED,
                            link.getRationale(),
                            "deployment-correlator",
                            deployment.getOccurredAt())
                    .withMetadata(Map.of(
                            "deploymentId", deployment.getId().toString(),
                            "serviceKey", deployment.getServiceKey(),
                            "version", deployment.getVersionLabel(),
                            "suspicionScore", link.getSuspicionScore())));
        }
    }
}
