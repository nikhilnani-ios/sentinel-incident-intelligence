package io.sentinel.incident.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.sentinel.platform.domain.model.EscalationStep;
import io.sentinel.platform.domain.model.Incident;

/**
 * Delivers an escalation to its target.
 *
 * <p>Logging is the deliberate default implementation. Wiring a real pager (PagerDuty, Twilio,
 * Slack) is an integration detail behind this seam; keeping it as one interface means the escalation
 * logic can be tested end to end without a paid account or a stubbed HTTP server.
 */
@Component
public class Notifier {

    private static final Logger log = LoggerFactory.getLogger(Notifier.class);

    public void notify(Incident incident, EscalationStep step) {
        String message = "[%s] %s — %s (severity %s, %d signals)"
                .formatted(
                        incident.getIncidentKey(),
                        incident.getTitle(),
                        incident.getPrimaryServiceKey(),
                        incident.getSeverity(),
                        incident.getSignalCount());

        switch (step.getTargetType()) {
            case USER -> log.warn("PAGE user {} :: {}", step.getTarget(), message);
            case TEAM -> log.warn("PAGE team {} :: {}", step.getTarget(), message);
            case WEBHOOK -> log.warn("POST webhook {} :: {}", step.getTarget(), message);
        }
    }
}
