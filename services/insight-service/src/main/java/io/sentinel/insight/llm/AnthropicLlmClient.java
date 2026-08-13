package io.sentinel.insight.llm;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;

/**
 * Calls the Anthropic Messages API.
 *
 * <p>Wrapped in a retry and a circuit breaker because model APIs fail in ways that databases do not:
 * transient 529s under load, occasional multi-second latency spikes, and hard rate limits. The
 * circuit breaker matters most — when analysis is degraded the correct behaviour is to fail fast and
 * let the responder work from the timeline, not to queue requests behind a provider outage during
 * the exact minutes an incident is active.
 *
 * <p>Only registered when an API key is present; otherwise {@link StubLlmClient} takes over so the
 * platform runs end to end with no credentials.
 */
@Component
@ConditionalOnExpression("'${sentinel.insight.anthropic.api-key:}'.length() > 0")
public class AnthropicLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicLlmClient.class);
    private static final String API_VERSION = "2023-06-01";

    private final WebClient webClient;
    private final String model;
    private final Duration timeout;

    public AnthropicLlmClient(
            WebClient.Builder webClientBuilder,
            @org.springframework.beans.factory.annotation.Value("${sentinel.insight.anthropic.api-key}") String apiKey,
            @org.springframework.beans.factory.annotation.Value(
                            "${sentinel.insight.anthropic.base-url:https://api.anthropic.com}")
                    String baseUrl,
            @org.springframework.beans.factory.annotation.Value(
                            "${sentinel.insight.anthropic.model:claude-sonnet-4-20250514}")
                    String model,
            @org.springframework.beans.factory.annotation.Value("${sentinel.insight.anthropic.timeout:PT60S}")
                    Duration timeout) {
        this.webClient = webClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", API_VERSION)
                .defaultHeader("content-type", "application/json")
                .build();
        this.model = model;
        this.timeout = timeout;
    }

    @Override
    @Retry(name = "llm")
    @CircuitBreaker(name = "llm", fallbackMethod = "unavailable")
    public Completion complete(Prompt prompt) {
        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", prompt.maxTokens(),
                "temperature", 0,
                "system", prompt.system(),
                "messages", List.of(Map.of("role", "user", "content", prompt.user())));

        JsonNode response = webClient
                .post()
                .uri("/v1/messages")
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::translateError)
                .bodyToMono(JsonNode.class)
                .timeout(timeout)
                .block();

        return parse(response);
    }

    @Override
    public String modelName() {
        return model;
    }

    Completion parse(JsonNode response) {
        if (response == null) {
            throw new LlmUnavailableException("Empty response from the model API");
        }

        StringBuilder text = new StringBuilder();
        JsonNode content = response.path("content");
        for (JsonNode block : content) {
            if ("text".equals(block.path("type").asText())) {
                text.append(block.path("text").asText());
            }
        }

        if (text.isEmpty()) {
            throw new LlmUnavailableException("Model API returned no text content");
        }

        String stopReason = response.path("stop_reason").asText();
        if ("max_tokens".equals(stopReason)) {
            log.warn("Model response reached the configured output-token limit; JSON may be incomplete");
        }

        JsonNode usage = response.path("usage");
        return new Completion(
                text.toString(),
                response.path("model").asText(model),
                usage.path("input_tokens").asInt(),
                usage.path("output_tokens").asInt());
    }

    private reactor.core.publisher.Mono<Throwable> translateError(
            org.springframework.web.reactive.function.client.ClientResponse response) {
        return response.releaseBody().then(reactor.core.publisher.Mono.<Throwable>fromSupplier(() -> {
            int status = response.statusCode().value();
            String requestId =
                    response.headers().header("request-id").stream().findFirst().orElse("unknown");
            // Do not include the provider response body: it can echo request data into application logs.
            // 4xx other than 429 will never succeed on retry, so surface them as permanent.
            if (status >= 400 && status < 500 && status != 429) {
                return new LlmRequestRejectedException(
                        "Model API rejected the request (%d, request-id %s)".formatted(status, requestId));
            }
            return new LlmUnavailableException("Model API returned %d (request-id %s)".formatted(status, requestId));
        }));
    }

    /** Resilience4j fallback: invoked when the breaker is open or every retry has been used. */
    @SuppressWarnings("unused")
    private Completion unavailable(Prompt prompt, Throwable cause) {
        if (cause instanceof LlmRequestRejectedException rejected) {
            throw rejected;
        }
        log.warn("Model API unavailable, refusing to generate analysis: {}", cause.getMessage());
        throw new LlmUnavailableException("Analysis is temporarily unavailable", cause);
    }

    /** Retryable: the provider is down, throttling, or timing out. */
    public static class LlmUnavailableException extends RuntimeException {
        public LlmUnavailableException(String message) {
            super(message);
        }

        public LlmUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Not retryable: the request itself is wrong (bad key, oversized prompt, bad model name). */
    public static class LlmRequestRejectedException extends RuntimeException {
        public LlmRequestRejectedException(String message) {
            super(message);
        }
    }
}
