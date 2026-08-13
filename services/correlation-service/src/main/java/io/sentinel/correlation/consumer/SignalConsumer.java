package io.sentinel.correlation.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import io.sentinel.correlation.service.CorrelationEngine;
import io.sentinel.platform.common.event.SignalEnvelope;
import io.sentinel.platform.common.kafka.Topics;

/**
 * Entry point for alert, metric and log signals.
 *
 * <p>One listener for all three topics: the correlation logic is identical, and separate listeners
 * would only mean three copies of the same error handling. Retry, backoff and dead-lettering are
 * configured centrally in {@code KafkaConsumerConfig}, so this class stays a thin adapter — it
 * translates a Kafka record into a domain call and nothing else.
 *
 * <p>Acknowledgement is manual and happens only after the transaction commits, so a crash mid-
 * correlation replays the signal rather than losing it.
 */
@Component
public class SignalConsumer {

    private static final Logger log = LoggerFactory.getLogger(SignalConsumer.class);

    private final CorrelationEngine correlationEngine;

    public SignalConsumer(CorrelationEngine correlationEngine) {
        this.correlationEngine = correlationEngine;
    }

    @KafkaListener(
            topics = {Topics.SIGNALS_ALERTS, Topics.SIGNALS_METRICS, Topics.SIGNALS_LOGS},
            groupId = "correlation-engine",
            containerFactory = "kafkaListenerContainerFactory")
    public void onSignal(
            @Payload SignalEnvelope envelope,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {

        MDC.put("tenantId", envelope.tenantId());
        MDC.put("eventId", envelope.eventId());
        try {
            CorrelationEngine.Outcome outcome = correlationEngine.correlate(envelope);
            log.debug("{}@{} -> {}", topic, offset, outcome.action());
            acknowledgment.acknowledge();
        } finally {
            MDC.remove("tenantId");
            MDC.remove("eventId");
        }
    }
}
