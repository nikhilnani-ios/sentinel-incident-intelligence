package io.sentinel.correlation.service;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.sentinel.platform.common.event.AlertPayload;
import io.sentinel.platform.common.event.LogPayload;
import io.sentinel.platform.common.event.MetricPayload;
import io.sentinel.platform.common.event.SignalEnvelope;
import io.sentinel.platform.domain.model.Incident;
import io.sentinel.platform.domain.model.IncidentSignal;
import io.sentinel.platform.domain.repository.IncidentRepository;

/** Builds incidents and incident signals from envelopes, including the human-facing title. */
@Component
public class IncidentFactory {

    private static final String DEFAULT_POLICY = "default-critical";

    private final IncidentRepository incidentRepository;

    public IncidentFactory(IncidentRepository incidentRepository) {
        this.incidentRepository = incidentRepository;
    }

    public Incident newIncident(SignalEnvelope trigger) {
        Incident incident = new Incident(
                trigger.tenantId(),
                nextIncidentKey(),
                titleFor(trigger),
                trigger.severity(),
                trigger.serviceKey(),
                trigger.occurredAt());
        incident.setEscalationPolicyKey(DEFAULT_POLICY);
        return incident;
    }

    public IncidentSignal newSignal(SignalEnvelope envelope, String fingerprint, double correlationScore) {
        return new IncidentSignal(
                fingerprint,
                envelope.type(),
                envelope.serviceKey(),
                envelope.severity(),
                envelope.payload().summary(),
                correlationScore,
                envelope.occurredAt(),
                envelope.labels(),
                detailOf(envelope));
    }

    /**
     * Titles are generated from the triggering signal because they are what an engineer reads first
     * on a phone at 3am. Service name leads, since that is what determines who should be looking.
     */
    private String titleFor(SignalEnvelope trigger) {
        return switch (trigger.payload()) {
            case AlertPayload alert -> "%s: %s".formatted(trigger.serviceKey(), alert.alertName());
            case MetricPayload metric -> "%s: %s breached threshold"
                    .formatted(trigger.serviceKey(), metric.metricName());
            case LogPayload logPayload -> "%s: error burst (%dx)"
                    .formatted(trigger.serviceKey(), logPayload.occurrences());
            default -> "%s: degraded".formatted(trigger.serviceKey());
        };
    }

    private String nextIncidentKey() {
        return "INC-" + incidentRepository.nextIncidentNumber();
    }

    /**
     * Flattens the payload into a map for the jsonb column. Kept explicit rather than reflecting over
     * the record so the stored shape is a deliberate contract the UI and prompts can rely on.
     */
    private Map<String, Object> detailOf(SignalEnvelope envelope) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("eventId", envelope.eventId());
        detail.put("kind", envelope.type().name());

        switch (envelope.payload()) {
            case AlertPayload alert -> {
                detail.put("alertName", alert.alertName());
                detail.put("description", alert.description());
                detail.put("runbookUrl", alert.runbookUrl());
                detail.put("source", alert.source());
                detail.put("resolved", alert.resolved());
            }
            case MetricPayload metric -> {
                detail.put("metricName", metric.metricName());
                detail.put("value", metric.value());
                detail.put("unit", metric.unit());
                detail.put("threshold", metric.threshold());
                detail.put("comparison", String.valueOf(metric.comparison()));
            }
            case LogPayload logPayload -> {
                detail.put("level", logPayload.level());
                detail.put("message", logPayload.message());
                detail.put("loggerName", logPayload.loggerName());
                detail.put("traceId", logPayload.traceId());
                detail.put("stackTrace", logPayload.stackTrace());
                detail.put("occurrences", logPayload.occurrences());
            }
            case io.sentinel.platform.common.event.DeploymentPayload deployment -> {
                detail.put("version", deployment.version());
                detail.put("commitSha", deployment.commitSha());
                detail.put("environment", deployment.environment());
                detail.put("status", deployment.status().name());
            }
        }
        detail.values().removeIf(java.util.Objects::isNull);
        return detail;
    }
}
