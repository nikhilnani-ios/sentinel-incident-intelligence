package io.sentinel.correlation.publisher;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.sentinel.platform.common.event.IncidentEvent;
import io.sentinel.platform.common.kafka.Topics;
import io.sentinel.platform.domain.model.Incident;

/**
 * Publishes incident changes for anything that wants to react to them.
 *
 * <p>Publication is deferred until after the transaction commits. Publishing inside the transaction
 * is the classic dual-write bug: the browser receives "incident created", opens it, and gets a 404
 * because the insert has not committed yet — or worse, the transaction rolls back and the event is
 * already gone.
 *
 * <p>This is a pragmatic middle ground rather than a full transactional outbox. If the process dies
 * between commit and publish the event is lost, which for a UI-refresh signal costs one stale panel
 * until the next poll. If we later need at-least-once delivery, the fix is an outbox table written
 * in the same transaction and drained by a relay — the call sites here would not change.
 */
@Component
public class IncidentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(IncidentEventPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public IncidentEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(Incident incident, IncidentEvent.Change change, String actor) {
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
                    send(event);
                }
            });
        } else {
            send(event);
        }
    }

    private void send(IncidentEvent event) {
        kafkaTemplate
                .send(Topics.INCIDENT_EVENTS, event.tenantId() + ':' + event.incidentId(), event)
                .whenComplete((result, error) -> {
                    if (error != null) {
                        log.error("Failed to publish {} for incident {}", event.change(), event.incidentId(), error);
                    }
                });
    }
}
