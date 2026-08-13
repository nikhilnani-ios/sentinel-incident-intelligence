package io.sentinel.insight.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.sentinel.insight.service.RcaResponseParser;
import io.sentinel.insight.service.RcaResult;

class RcaResponseParserTest {

    private final RcaResponseParser parser = new RcaResponseParser(new ObjectMapper());

    @Test
    @DisplayName("parses a well-formed response")
    void parsesCleanJson() {
        RcaResult result = parser.parse(
                """
                {
                  "headline": "Checkout failing after payment-gateway deploy",
                  "summary": "Error rate rose four minutes after v2.4.1 shipped.",
                  "confidence": 0.82,
                  "hypotheses": [
                    {"cause": "Bad deploy", "reasoning": "Timing lines up", "likelihood": 0.8, "nextStep": "Roll back"}
                  ],
                  "immediateActions": ["Roll back v2.4.1"]
                }
                """);

        assertThat(result.headline()).startsWith("Checkout failing");
        assertThat(result.confidence()).isEqualTo(0.82);
        assertThat(result.hypotheses()).hasSize(1);
        assertThat(result.immediateActions()).containsExactly("Roll back v2.4.1");
    }

    @Test
    @DisplayName("strips markdown fences and preamble the model was told not to add")
    void toleratesFencedJson() {
        RcaResult result = parser.parse(
                """
                Here is my analysis:

                ```json
                {"headline": "Cache eviction storm", "summary": "s", "confidence": 0.5, "hypotheses": []}
                ```
                """);

        assertThat(result.headline()).isEqualTo("Cache eviction storm");
        assertThat(result.confidence()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("orders hypotheses by likelihood so the top suspect leads")
    void ordersHypotheses() {
        RcaResult result = parser.parse(
                """
                {"headline": "h", "summary": "s", "confidence": 0.4, "hypotheses": [
                  {"cause": "Unlikely", "likelihood": 0.1},
                  {"cause": "Likely", "likelihood": 0.9},
                  {"cause": "Maybe", "likelihood": 0.5}
                ]}
                """);

        assertThat(result.hypotheses())
                .extracting(RcaResult.Hypothesis::cause)
                .containsExactly("Likely", "Maybe", "Unlikely");
    }

    @Test
    @DisplayName("clamps out-of-range scores rather than trusting them")
    void clampsScores() {
        RcaResult result = parser.parse(
                """
                {"headline": "h", "summary": "s", "confidence": 4.2, "hypotheses": [
                  {"cause": "c", "likelihood": -1.0}
                ]}
                """);

        assertThat(result.confidence()).isEqualTo(1.0);
        assertThat(result.hypotheses().get(0).likelihood()).isZero();
    }

    @Test
    @DisplayName("degrades to raw text instead of throwing when the response is not JSON")
    void degradesGracefully() {
        RcaResult result = parser.parse("I was unable to determine a cause from this evidence.");

        assertThat(result.headline()).contains("unstructured");
        assertThat(result.summary()).contains("unable to determine");
        assertThat(result.confidence()).isZero();
        assertThat(result.hypotheses()).isEmpty();
    }

    @Test
    @DisplayName("an empty response does not blow up the incident view")
    void handlesEmptyResponse() {
        assertThat(parser.parse("").confidence()).isZero();
        assertThat(parser.parse(null).headline()).isNotBlank();
    }

    @Test
    @DisplayName("limits hypotheses even when a model returns an oversized array")
    void limitsHypotheses() {
        RcaResult result = parser.parse(
                """
                {"headline":"h","summary":"s","confidence":0.5,"hypotheses":[
                  {"cause":"1","likelihood":0.1},{"cause":"2","likelihood":0.2},
                  {"cause":"3","likelihood":0.3},{"cause":"4","likelihood":0.4},
                  {"cause":"5","likelihood":0.5},{"cause":"6","likelihood":0.9}
                ]}
                """);

        assertThat(result.hypotheses()).hasSize(5);
    }

    @Test
    @DisplayName("an invalid JSON object degrades without exposing an exception to responders")
    void handlesInvalidJsonObject() {
        RcaResult result = parser.parse("model preamble {not valid json} trailing text");

        assertThat(result.headline()).contains("unstructured");
        assertThat(result.summary()).contains("not valid json");
    }
}
