package io.sentinel.insight.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.reactive.function.client.WebClient;

class LlmClientSelectionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(WebClient.Builder.class, WebClient::builder)
            .withUserConfiguration(DemoLlmClient.class, StubLlmClient.class, AnthropicLlmClient.class);

    @Test
    void demoIsThePublicSafeDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(LlmClient.class);
            assertThat(context.getBean(LlmClient.class)).isInstanceOf(DemoLlmClient.class);
        });
    }

    @Test
    void stubCanBeSelectedExplicitly() {
        contextRunner.withPropertyValues("sentinel.insight.mode=stub").run(context -> {
            assertThat(context).hasSingleBean(LlmClient.class);
            assertThat(context.getBean(LlmClient.class)).isInstanceOf(StubLlmClient.class);
        });
    }

    @Test
    void anthropicModeWithoutAKeyFallsBackWithoutSpend() {
        contextRunner.withPropertyValues("sentinel.insight.mode=anthropic").run(context -> {
            assertThat(context).hasSingleBean(LlmClient.class);
            assertThat(context.getBean(LlmClient.class)).isInstanceOf(StubLlmClient.class);
        });
    }

    @Test
    void anthropicRequiresBothModeAndKey() {
        contextRunner
                .withPropertyValues("sentinel.insight.mode=anthropic", "sentinel.insight.anthropic.api-key=test-key")
                .run(context -> {
                    assertThat(context).hasSingleBean(LlmClient.class);
                    assertThat(context.getBean(LlmClient.class)).isInstanceOf(AnthropicLlmClient.class);
                });
    }
}
