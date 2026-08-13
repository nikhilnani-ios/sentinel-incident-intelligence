package io.sentinel.platform.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import io.sentinel.platform.common.event.AlertPayload;
import io.sentinel.platform.common.event.LogPayload;
import io.sentinel.platform.common.event.MetricPayload;
import io.sentinel.platform.common.event.SignalEnvelope;

/**
 * Builds the stable identity of a signal, used for deduplication.
 *
 * <p>Two signals share a fingerprint when a human would call them "the same alert firing again".
 * That means we hash the things that define the problem (service, alert name, severity, meaningful
 * labels) and deliberately exclude the things that change on every fire (timestamps, event ids,
 * pod names, replica ordinals, trace ids).
 */
public final class Fingerprints {

    /**
     * Labels excluded from the fingerprint because they identify the emitter, not the problem.
     * Without this, a crash-looping deployment produces one incident per pod restart.
     */
    private static final Set<String> VOLATILE_LABELS =
            Set.of("pod", "instance", "container_id", "replica", "hostname", "trace_id", "span_id", "request_id");

    /** Digits inside a log message (ids, durations, counts) are noise for grouping purposes. */
    private static final Pattern NUMERIC_RUN = Pattern.compile("\\d+");

    private static final Pattern UUID_LIKE =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", Pattern.CASE_INSENSITIVE);

    private Fingerprints() {}

    public static String of(SignalEnvelope envelope) {
        StringBuilder material = new StringBuilder()
                .append(envelope.tenantId())
                .append('|')
                .append(envelope.serviceKey())
                .append('|')
                .append(envelope.type())
                .append('|');

        switch (envelope.payload()) {
            case AlertPayload alert -> material.append(alert.alertName())
                    .append('|')
                    .append(alert.severity())
                    .append('|')
                    .append(alert.source());
            case MetricPayload metric -> material.append(metric.metricName())
                    .append('|')
                    .append(metric.comparison());
            case LogPayload log -> material.append(log.level()).append('|').append(normaliseMessage(log.message()));
            default -> material.append(envelope.payload().summary());
        }

        material.append('|').append(stableLabels(envelope.labels()));
        return sha256(material.toString());
    }

    /**
     * Collapses the variable parts of a log line so that "timeout after 3011ms calling order-svc"
     * and "timeout after 4522ms calling order-svc" group together.
     */
    static String normaliseMessage(String message) {
        String withoutIds = UUID_LIKE.matcher(message).replaceAll("<uuid>");
        String withoutNumbers = NUMERIC_RUN.matcher(withoutIds).replaceAll("<n>");
        return withoutNumbers.toLowerCase().trim();
    }

    private static String stableLabels(Map<String, String> labels) {
        Map<String, String> sorted = new TreeMap<>();
        labels.forEach((key, value) -> {
            if (!VOLATILE_LABELS.contains(key.toLowerCase())) {
                sorted.put(key.toLowerCase(), value);
            }
        });
        return sorted.toString();
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM spec", e);
        }
    }
}
