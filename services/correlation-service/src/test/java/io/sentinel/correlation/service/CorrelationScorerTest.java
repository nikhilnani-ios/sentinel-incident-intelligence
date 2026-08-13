package io.sentinel.correlation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.sentinel.correlation.config.CorrelationProperties;
import io.sentinel.correlation.graph.ServiceGraph;
import io.sentinel.platform.common.event.AlertPayload;
import io.sentinel.platform.common.event.Severity;
import io.sentinel.platform.common.event.SignalEnvelope;
import io.sentinel.platform.common.event.SignalType;
import io.sentinel.platform.domain.model.Incident;
import io.sentinel.platform.domain.model.IncidentSignal;
import io.sentinel.platform.domain.model.ServiceDependency;

/**
 * The scoring rules are the product decision that matters most: too loose and unrelated failures get
 * merged into one unreadable incident, too tight and a cascade pages four teams separately.
 */
class CorrelationScorerTest {

    private static final Instant DETECTED_AT = Instant.parse("2026-03-01T12:00:00Z");
    private static final double ATTACH_THRESHOLD = 0.55;

    private final CorrelationProperties properties = new CorrelationProperties(
            Duration.ofMinutes(5),
            Duration.ofMinutes(15),
            3,
            ATTACH_THRESHOLD,
            Duration.ofHours(1),
            Duration.ofMinutes(5),
            new CorrelationProperties.Weights(0.45, 0.30, 0.10, 0.15));

    private final CorrelationScorer scorer = new CorrelationScorer(properties);

    private final ServiceGraph graph = ServiceGraph.from(List.of(
            edge("edge-gateway", "checkout-api", 0.95),
            edge("checkout-api", "payment-gateway", 0.90),
            edge("checkout-api", "orders-postgres", 0.95),
            edge("search-api", "inventory-api", 0.30)));

    @Test
    @DisplayName("the same service at the same moment scores near the maximum")
    void sameServiceScoresHighest() {
        Incident incident = incident("checkout-api", Severity.CRITICAL, Map.of("region", "us-east-1"));
        SignalEnvelope signal =
                alert("checkout-api", Severity.CRITICAL, DETECTED_AT.plusSeconds(30), Map.of("region", "us-east-1"));

        CorrelationScorer.Score score = scorer.score(signal, incident, graph);

        assertThat(score.graphProximity()).isEqualTo(1.0);
        assertThat(score.total()).isGreaterThan(0.9);
    }

    @Test
    @DisplayName("a caller alerting right after its dependency clears the attach threshold")
    void adjacentServiceCorrelates() {
        Incident incident = incident("orders-postgres", Severity.CRITICAL, Map.of("region", "us-east-1"));
        SignalEnvelope signal =
                alert("checkout-api", Severity.HIGH, DETECTED_AT.plusSeconds(45), Map.of("region", "us-east-1"));

        CorrelationScorer.Score score = scorer.score(signal, incident, graph);

        assertThat(score.total()).isGreaterThanOrEqualTo(ATTACH_THRESHOLD);
    }

    @Test
    @DisplayName("an unrelated service at the same moment stays below the threshold")
    void unrelatedServiceDoesNotCorrelate() {
        Incident incident = incident("orders-postgres", Severity.CRITICAL, Map.of("region", "us-east-1"));
        SignalEnvelope signal =
                alert("search-api", Severity.HIGH, DETECTED_AT.plusSeconds(20), Map.of("region", "us-east-1"));

        CorrelationScorer.Score score = scorer.score(signal, incident, graph);

        assertThat(score.graphProximity()).isZero();
        assertThat(score.total()).isLessThan(ATTACH_THRESHOLD);
    }

    @Test
    @DisplayName("the same alert an hour later is a separate incident, not a continuation")
    void timeDecayBreaksCorrelation() {
        Incident incident = incident("checkout-api", Severity.CRITICAL, Map.of("region", "us-east-1"));
        SignalEnvelope signal = alert(
                "checkout-api",
                Severity.CRITICAL,
                DETECTED_AT.plus(Duration.ofHours(1)),
                Map.of("region", "us-east-1"));

        CorrelationScorer.Score score = scorer.score(signal, incident, graph);

        assertThat(score.timeProximity()).isZero();
    }

    @Test
    @DisplayName("time proximity halves at a quarter of the correlation window")
    void timeDecayFollowsHalfLife() {
        Incident incident = incident("checkout-api", Severity.HIGH, Map.of());
        SignalEnvelope signal = alert(
                "checkout-api",
                Severity.HIGH,
                DETECTED_AT.plus(Duration.ofMinutes(15).dividedBy(4)),
                Map.of());

        assertThat(scorer.score(signal, incident, graph).timeProximity()).isBetween(0.45, 0.55);
    }

    @Test
    @DisplayName("a different region is weaker evidence than a matching one")
    void labelMismatchLowersScore() {
        Incident incident = incident("checkout-api", Severity.CRITICAL, Map.of("region", "us-east-1"));

        double matching = scorer.score(
                        alert(
                                "checkout-api",
                                Severity.CRITICAL,
                                DETECTED_AT.plusSeconds(30),
                                Map.of("region", "us-east-1")),
                        incident,
                        graph)
                .total();
        double mismatched = scorer.score(
                        alert(
                                "checkout-api",
                                Severity.CRITICAL,
                                DETECTED_AT.plusSeconds(30),
                                Map.of("region", "eu-west-1")),
                        incident,
                        graph)
                .total();

        assertThat(matching).isGreaterThan(mismatched);
    }

    @Test
    @DisplayName("an INFO signal does not get pulled into a CRITICAL incident on severity alone")
    void severityGapReducesAffinity() {
        Incident incident = incident("orders-postgres", Severity.CRITICAL, Map.of());
        SignalEnvelope signal = alert("payment-gateway", Severity.INFO, DETECTED_AT.plusSeconds(30), Map.of());

        assertThat(scorer.score(signal, incident, graph).severityAffinity()).isLessThan(0.4);
    }

    @Test
    @DisplayName("score is always inside 0..1 regardless of the inputs")
    void scoreStaysNormalised() {
        Incident incident = incident("checkout-api", Severity.CRITICAL, Map.of("a", "1", "b", "2"));

        for (Severity severity : Severity.values()) {
            CorrelationScorer.Score score =
                    scorer.score(alert("checkout-api", severity, DETECTED_AT, Map.of("a", "1")), incident, graph);
            assertThat(score.total()).isBetween(0.0, 1.0);
        }
    }

    // ------------------------------------------------------------------ fixtures

    private Incident incident(String serviceKey, Severity severity, Map<String, String> labels) {
        Incident incident = new Incident("acme", "INC-1", serviceKey + " degraded", severity, serviceKey, DETECTED_AT);
        incident.attach(new IncidentSignal(
                "fp-seed", SignalType.ALERT, serviceKey, severity, "seed signal", 1.0, DETECTED_AT, labels, Map.of()));
        return incident;
    }

    private SignalEnvelope alert(String serviceKey, Severity severity, Instant occurredAt, Map<String, String> labels) {
        return new SignalEnvelope(
                UUID.randomUUID().toString(),
                "acme",
                serviceKey,
                occurredAt,
                occurredAt,
                labels,
                new AlertPayload("SomeAlert", severity, "description", null, "prometheus", false, Map.of()));
    }

    private ServiceDependency edge(String source, String target, double criticality) {
        ServiceDependency dependency = new ServiceDependency("acme", source, target, ServiceDependency.Kind.SYNC);
        dependency.setCriticality(criticality);
        return dependency;
    }
}
