package io.sentinel.incident.service;

import java.time.Instant;
import java.util.List;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.sentinel.incident.stream.IncidentStreamRegistry;
import io.sentinel.platform.common.event.IncidentEvent;
import io.sentinel.platform.common.kafka.Topics;
import io.sentinel.platform.domain.model.Incident;

/**
 * Publishes an incident change to Kafka after commit, and to locally connected browsers immediately.
 *
 * <p>The local push is not redundant with Kafka. The operator who clicked "acknowledge" is
 * connected to <em>this</em> replica, and going out to Kafka and back adds latency to the one
 * interaction where responsiveness is most visible. Kafka still carries the event so every other
 * replica — and any future notifier — sees it too, and {@link IncidentStreamRegistry} drops the
 * echo by event id.
 */
@Component
public class IncidentEventBroadcaster {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final IncidentStreamRegistry streamRegistry;

    public IncidentEventBroadcaster(
            KafkaTemplate<String, Object> kafkaTemplate, IncidentStreamRegistry streamRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.streamRegistry = streamRegistry;
    }

    public void broadcast(Incident incident, IncidentEvent.Change change, String actor) {
        IncidentEvent event = new IncidentEvent(
                incident.getId(),
                incident.getTenantId(),
                change,
                incident.getStatus(),
                incident.getSeverity(),
                incident.getTitle(),
                incident.getPrimaryServiceKey(),
                List.copyOf(incident.affectedServiceKeySet()),
                actor,
                Instant.now());

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch(event);
                }
            });
        } else {
            dispatch(event);
        }
    }

    private void dispatch(IncidentEvent event) {
        streamRegistry.push(event);
        kafkaTemplate.send(Topics.INCIDENT_EVENTS, event.tenantId() + ':' + event.incidentId(), event);
    }
}
