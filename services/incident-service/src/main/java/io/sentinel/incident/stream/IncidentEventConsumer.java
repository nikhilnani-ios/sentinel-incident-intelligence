package io.sentinel.incident.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import io.sentinel.platform.common.event.IncidentEvent;
import io.sentinel.platform.common.kafka.Topics;

/**
 * Bridges Kafka incident events onto the SSE fan-out.
 *
 * <p>Every replica needs every event — an operator connected to replica B must see an incident
 * created by replica A — so the group id includes the instance id. Sharing one group would give the
 * partitions to a single replica and leave the others silent.
 */
@Component
public class IncidentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(IncidentEventConsumer.class);

    private final IncidentStreamRegistry streamRegistry;

    public IncidentEventConsumer(IncidentStreamRegistry streamRegistry) {
        this.streamRegistry = streamRegistry;
    }

    @KafkaListener(
            topics = Topics.INCIDENT_EVENTS,
            groupId = "incident-stream-${HOSTNAME:local}",
            containerFactory = "kafkaListenerContainerFactory",
            // A replica that has been down has nothing useful to say about incidents it missed;
            // the browser reloads the list on reconnect anyway. Start from the live edge.
            properties = {"auto.offset.reset=latest"})
    public void onIncidentEvent(@Payload IncidentEvent event, Acknowledgment acknowledgment) {
        log.debug("Fanning out {} for incident {}", event.change(), event.incidentId());
        streamRegistry.push(event);
        acknowledgment.acknowledge();
    }
}
