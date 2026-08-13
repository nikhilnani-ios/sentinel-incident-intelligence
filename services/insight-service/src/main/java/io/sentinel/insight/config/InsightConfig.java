package io.sentinel.insight.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class InsightConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder()
                // Model responses run to several KB of JSON; the 256KB default buffer is generous
                // but a long postmortem can approach it, so raise it once here rather than debug it
                // later from a truncated response.
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(2 * 1024 * 1024));
    }
}
