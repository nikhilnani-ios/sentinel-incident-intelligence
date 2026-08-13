package io.sentinel.insight.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PromptBuilderTest {

    private static final Instant DETECTED_AT = Instant.parse("2026-03-01T12:00:00Z");

    private final PromptBuilder builder = new PromptBuilder();

    @Test
    @DisplayName("includes the facts an engineer would need to judge the answer")
    void includesCoreFacts() {
        String prompt = builder.render(context(List.of(signal("checkout-api", "HighErrorRate", 12))));

        assertThat(prompt)
                .contains("INC-1001")
                .contains("Primary service: checkout-api")
                .contains("HighErrorRate")
                .contains("(x12)")
                .contains("payment-gateway -> orders-postgres")
                .startsWith("<INCIDENT_EVIDENCE>")
                .endsWith("</INCIDENT_EVIDENCE>\n");
    }

    @Test
    @DisplayName("surfaces the top deployment suspect explicitly")
    void surfacesTopSuspect() {
        String prompt = builder.render(context(List.of(signal("checkout-api", "HighErrorRate", 1))));

        assertThat(prompt).contains("Most suspicious deployment: payment-gateway v2.4.1");
        assertThat(prompt).contains("4 minutes before detection");
    }

    @Test
    @DisplayName("caps signal volume so a noisy incident cannot blow the context window")
    void truncatesSignals() {
        List<IncidentContext.Signal> many = IntStream.range(0, 200)
                .mapToObj(i -> signal("service-" + i, "alert-" + i, 1))
                .toList();

        String prompt = builder.render(context(many));

        assertThat(prompt).contains("200 total, showing first 25");
        assertThat(prompt).contains("alert-0").doesNotContain("alert-199");
    }

    @Test
    @DisplayName("says so plainly when there were no deployments to blame")
    void statesAbsenceOfDeployments() {
        IncidentContext bare = new IncidentContext(
                "INC-2",
                "t",
                "HIGH",
                "OPEN",
                "search-api",
                List.of("search-api"),
                DETECTED_AT,
                5L,
                List.of(),
                List.of(),
                List.of(),
                List.of());

        assertThat(builder.render(bare)).contains("No deployments to affected services");
    }

    @Test
    @DisplayName("the system prompt demands calibration, not confidence")
    void systemPromptAsksForCalibration() {
        assertThat(builder.rcaSystemPrompt())
                .contains("lower your confidence")
                .contains("Never invent")
                .contains("untrusted operational data")
                .contains("JSON object");
        assertThat(builder.postmortemSystemPrompt()).contains("Blameless");
    }

    private IncidentContext context(List<IncidentContext.Signal> signals) {
        return new IncidentContext(
                "INC-1001",
                "checkout-api: HighErrorRate",
                "CRITICAL",
                "OPEN",
                "checkout-api",
                List.of("checkout-api", "payment-gateway"),
                DETECTED_AT,
                18L,
                signals,
                List.of(new IncidentContext.TimelineMoment(
                        "DETECTED", "Incident opened", "correlation-engine", DETECTED_AT)),
                List.of(new IncidentContext.SuspectDeployment(
                        "payment-gateway", "v2.4.1", "9f2c1ab", DETECTED_AT.minusSeconds(240), 4, 0.86)),
                List.of(new IncidentContext.DependencyEdge("payment-gateway", "orders-postgres", "DATASTORE", 0.9)));
    }

    private IncidentContext.Signal signal(String serviceKey, String summary, int occurrences) {
        return new IncidentContext.Signal("ALERT", serviceKey, "CRITICAL", summary, occurrences, DETECTED_AT);
    }
}
