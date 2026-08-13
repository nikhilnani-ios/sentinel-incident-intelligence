package io.sentinel.correlation.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.sentinel.platform.common.event.AlertPayload;
import io.sentinel.platform.common.event.DeploymentPayload;
import io.sentinel.platform.common.event.LogPayload;
import io.sentinel.platform.common.event.MetricPayload;
import io.sentinel.platform.common.event.Severity;
import io.sentinel.platform.common.event.SignalEnvelope;

/**
 * Decides what a signal is allowed to do.
 *
 * <p>Two distinct questions, and conflating them is what makes naive incident tools unusable:
 *
 * <ul>
 *   <li><b>Can it open an incident?</b> Only signals that genuinely represent a problem. A single
 *       ERROR log line should never page anyone.
 *   <li><b>Can it join one?</b> Almost anything. Once an incident exists, a stream of INFO metrics
 *       and debug logs from the affected services is exactly the context a responder wants, and it
 *       is what the LLM reads to explain what happened.
 * </ul>
 */
@Component
public class SignalTriage {

    private final int logBurstThreshold;

    public SignalTriage(@Value("${sentinel.correlation.log-burst-threshold:25}") int logBurstThreshold) {
        this.logBurstThreshold = logBurstThreshold;
    }

    public boolean canOpenIncident(SignalEnvelope signal) {
        return switch (signal.payload()) {
                // A resolving alert closes a loop; it must never open one.
            case AlertPayload alert -> !alert.resolved() && alert.severity().isAtLeast(Severity.MEDIUM);
            case MetricPayload metric -> metric.breachesThreshold();
                // One stack trace is noise; a burst of the same trace is a symptom.
            case LogPayload logPayload -> logPayload.isError() && logPayload.occurrences() >= logBurstThreshold;
                // Deployments are context for incidents, never the trigger — a deploy is not an outage.
            case DeploymentPayload ignored -> false;
        };
    }

    /** Deployments are linked through {@code DeploymentCorrelator}, not attached as signals. */
    public boolean canAttachToIncident(SignalEnvelope signal) {
        return !(signal.payload() instanceof DeploymentPayload);
    }

    /** Alerts marked resolved close out their fingerprint so a recurrence pages again. */
    public boolean isResolutionSignal(SignalEnvelope signal) {
        return signal.payload() instanceof AlertPayload alert && alert.resolved();
    }
}
