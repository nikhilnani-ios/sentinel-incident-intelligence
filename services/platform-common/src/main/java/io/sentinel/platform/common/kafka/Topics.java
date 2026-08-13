package io.sentinel.platform.common.kafka;

/**
 * Topic names, versioned in the name itself.
 *
 * <p>A breaking schema change ships as {@code .v2} alongside {@code .v1} so producers and consumers
 * can be rolled independently; the old topic is retired once lag on it reaches zero.
 */
public final class Topics {

    public static final String SIGNALS_ALERTS = "signals.alerts.v1";
    public static final String SIGNALS_METRICS = "signals.metrics.v1";
    public static final String SIGNALS_LOGS = "signals.logs.v1";
    public static final String SIGNALS_DEPLOYMENTS = "signals.deployments.v1";
    public static final String INCIDENT_EVENTS = "incidents.events.v1";

    public static final String DLQ_SUFFIX = ".dlq";

    private Topics() {}

    public static String deadLetterFor(String topic) {
        return topic + DLQ_SUFFIX;
    }

    public static String forSignalType(io.sentinel.platform.common.event.SignalType type) {
        return switch (type) {
            case ALERT -> SIGNALS_ALERTS;
            case METRIC -> SIGNALS_METRICS;
            case LOG -> SIGNALS_LOGS;
            case DEPLOYMENT -> SIGNALS_DEPLOYMENTS;
        };
    }
}
