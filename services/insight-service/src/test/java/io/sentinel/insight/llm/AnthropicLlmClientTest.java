package io.sentinel.insight.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.ObjectMapper;

class AnthropicLlmClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AnthropicLlmClient client = new AnthropicLlmClient(
            WebClient.builder(), "test-key", "http://localhost", "test-model", Duration.ofSeconds(1).toString());

    @Test
    void parsesTextBlocksAndUsage() throws Exception {
        LlmClient.Completion completion = client.parse(
                objectMapper.readTree(
                        """
                {
                  "model":"claude-sonnet-4-20250514",
                  "stop_reason":"end_turn",
                  "content":[
                    {"type":"text","text":"database "},
                    {"type":"text","text":"saturation"}
                  ],
                  "usage":{"input_tokens":321,"output_tokens":47}
                }
                """));

        assertThat(completion.text()).isEqualTo("database saturation");
        assertThat(completion.model()).isEqualTo("claude-sonnet-4-20250514");
        assertThat(completion.inputTokens()).isEqualTo(321);
        assertThat(completion.outputTokens()).isEqualTo(47);
    }

    @Test
    void rejectsResponsesWithoutTextContent() throws Exception {
        assertThatThrownBy(() ->
                        client.parse(objectMapper.readTree("{\"model\":\"test-model\",\"content\":[],\"usage\":{}}")))
                .isInstanceOf(AnthropicLlmClient.LlmUnavailableException.class)
                .hasMessageContaining("no text");
    }
}
