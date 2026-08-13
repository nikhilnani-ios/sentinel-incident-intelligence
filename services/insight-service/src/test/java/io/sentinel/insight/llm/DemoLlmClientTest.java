package io.sentinel.insight.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class DemoLlmClientTest {

    private final DemoLlmClient client = new DemoLlmClient();

    @Test
    void returnsStructuredCostFreeAnalysis() throws Exception {
        LlmClient.Completion completion = client.complete(new LlmClient.Prompt(
                "live incident analysis",
                "Primary service: payment-gateway\nMost suspicious deployment: payment-gateway v2.4.1\n",
                2000));

        var json = new ObjectMapper().readTree(completion.text());
        assertThat(completion.model()).isEqualTo("sentinel-demo-v1");
        assertThat(completion.inputTokens()).isZero();
        assertThat(completion.outputTokens()).isZero();
        assertThat(json.path("confidence").asDouble()).isEqualTo(0.84);
        assertThat(json.path("hypotheses")).hasSize(2);
        assertThat(json.path("summary").asText()).contains("payment-gateway v2.4.1");
    }

    @Test
    void returnsPortfolioPostmortemWithoutCallingAProvider() {
        LlmClient.Completion completion = client.complete(
                new LlmClient.Prompt("write a blameless postmortem", "Primary service: payment-gateway", 4000));

        assertThat(completion.text())
                .contains("## Summary")
                .contains("## Customer impact")
                .contains("## Action items");
        assertThat(completion.model()).isEqualTo("sentinel-demo-v1");
    }
}
