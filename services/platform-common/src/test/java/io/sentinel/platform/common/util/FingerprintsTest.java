package io.sentinel.platform.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.sentinel.platform.common.event.AlertPayload;
import io.sentinel.platform.common.event.LogPayload;
import io.sentinel.platform.common.event.Severity;
import io.sentinel.platform.common.event.SignalEnvelope;

class FingerprintsTest {

    @Test
    @DisplayName("same alert from different pods produces one fingerprint")
    void ignoresVolatileLabels() {
        AlertPayload alert =
                new AlertPayload("HighErrorRate", Severity.HIGH, "5xx above 2%", null, "prometheus", false, Map.of());

        String first = Fingerprints.of(envelope(alert, Map.of("pod", "checkout-7f9c", "region", "us-east-1")));
        String second = Fingerprints.of(envelope(alert, Map.of("pod", "checkout-2b4d", "region", "us-east-1")));

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("different region is a genuinely different problem")
    void meaningfulLabelsChangeFingerprint() {
        AlertPayload alert =
                new AlertPayload("HighErrorRate", Severity.HIGH, "5xx above 2%", null, "prometheus", false, Map.of());

        String east = Fingerprints.of(envelope(alert, Map.of("region", "us-east-1")));
        String west = Fingerprints.of(envelope(alert, Map.of("region", "us-west-2")));

        assertThat(east).isNotEqualTo(west);
    }

    @Test
    @DisplayName("log lines that differ only by numbers group together")
    void normalisesNumericNoise() {
        String first = Fingerprints.of(envelope(
                new LogPayload("ERROR", "timeout after 3011ms calling order-svc", "http", null, null, 1), Map.of()));
        String second = Fingerprints.of(envelope(
                new LogPayload("ERROR", "timeout after 4522ms calling order-svc", "http", null, null, 1), Map.of()));

        assertThat(first).isEqualTo(second);
    }

    private SignalEnvelope envelope(
            io.sentinel.platform.common.event.SignalPayload payload, Map<String, String> labels) {
        return new SignalEnvelope(
                UUID.randomUUID().toString(),
                "acme",
                "checkout-api",
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:01Z"),
                labels,
                payload);
    }
}
