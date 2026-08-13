package io.sentinel.correlation.consumer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.sentinel.correlation.graph.ServiceGraphProvider;
import io.sentinel.correlation.service.DeploymentCorrelator;
import io.sentinel.platform.common.event.DeploymentPayload;
import io.sentinel.platform.common.event.SignalEnvelope;
import io.sentinel.platform.common.kafka.Topics;
import io.sentinel.platform.domain.model.Deployment;
import io.sentinel.platform.domain.repository.DeploymentRepository;
import io.sentinel.platform.domain.repository.IncidentRepository;

/**
 * Records deployments and back-fills correlation for incidents that were already open.
 *
 * <p>The ordering problem is real: a deploy at 14:00 and an incident detected at 14:02 usually
 * arrive in that order, but CI systems are slow and the deploy event can land afterwards. Rather
 * than assume ordering, an arriving deployment re-checks recent open incidents and links itself if
 * it is a plausible cause.
 */
@Component
public class DeploymentConsumer {

    private static final Logger log = LoggerFactory.getLogger(DeploymentConsumer.class);

    private final DeploymentRepository deploymentRepository;
    private final IncidentRepository incidentRepository;
    private final DeploymentCorrelator deploymentCorrelator;
    private final ServiceGraphProvider graphProvider;
    private final Duration backfillWindow;

    public DeploymentConsumer(
            DeploymentRepository deploymentRepository,
            IncidentRepository incidentRepository,
            DeploymentCorrelator deploymentCorrelator,
            ServiceGraphProvider graphProvider,
            @Value("${sentinel.correlation.deployment-backfill-window:PT30M}") Duration backfillWindow) {
        this.deploymentRepository = deploymentRepository;
        this.incidentRepository = incidentRepository;
        this.deploymentCorrelator = deploymentCorrelator;
        this.graphProvider = graphProvider;
        this.backfillWindow = backfillWindow;
    }

    @KafkaListener(
            topics = Topics.SIGNALS_DEPLOYMENTS,
            groupId = "correlation-engine",
            containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onDeployment(@Payload SignalEnvelope envelope, Acknowledgment acknowledgment) {
        if (!(envelope.payload() instanceof DeploymentPayload payload)) {
            log.warn("Ignoring non-deployment payload on the deployments topic: {}", envelope.eventId());
            acknowledgment.acknowledge();
            return;
        }

        Deployment deployment = persist(envelope, payload);
        backfillOpenIncidents(envelope, deployment);
        acknowledgment.acknowledge();
    }

    private Deployment persist(SignalEnvelope envelope, DeploymentPayload payload) {
        Deployment deployment = new Deployment(
                envelope.tenantId(),
                envelope.serviceKey(),
                payload.version(),
                payload.environment(),
                payload.status(),
                envelope.occurredAt());
        deployment.setCommitSha(payload.commitSha());
        deployment.setDeployedBy(payload.deployedBy());
        deployment.setChangelogUrl(payload.changelogUrl());

        Deployment saved = deploymentRepository.save(deployment);
        log.info(
                "Recorded deployment of {} {} to {} ({})",
                saved.getServiceKey(),
                saved.getVersionLabel(),
                saved.getEnvironment(),
                saved.getStatus());
        return saved;
    }

    /** Links this deploy into any open incident detected shortly after it. */
    private void backfillOpenIncidents(SignalEnvelope envelope, Deployment deployment) {
        Instant windowEnd = deployment.getOccurredAt().plus(backfillWindow);
        var graph = graphProvider.forTenant(envelope.tenantId());

        List<String> scope = java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(envelope.serviceKey()),
                        graph.blastRadius(envelope.serviceKey(), 3).keySet().stream())
                .toList();

        incidentRepository.findCorrelationCandidates(envelope.tenantId(), scope, deployment.getOccurredAt()).stream()
                .filter(incident -> incident.getDetectedAt().isBefore(windowEnd))
                .forEach(incident -> deploymentCorrelator.linkSuspects(incident, graph));
    }
}
