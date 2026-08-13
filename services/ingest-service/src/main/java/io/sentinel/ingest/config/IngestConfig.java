package io.sentinel.ingest.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IngestConfig {

    /** Injected rather than calling {@code Instant.now()} inline, so time can be frozen in tests. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
