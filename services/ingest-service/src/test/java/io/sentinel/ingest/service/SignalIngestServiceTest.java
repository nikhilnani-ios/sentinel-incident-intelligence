package io.sentinel.ingest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.sentinel.ingest.api.dto.IngestRequests.AlertRequest;
import io.sentinel.ingest.api.dto.IngestResponse;
import io.sentinel.platform.common.event.Severity;
import io.sentinel.platform.common.kafka.Topics;

@ExtendWith(MockitoExtension.class)
class SignalIngestServiceTest {

    private static final String TENANT = "acme";
    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Mock
    private IdempotencyGuard idempotencyGuard;

    @Mock
    private IngestRateLimiter rateLimiter;

    private SignalIngestService service;

    @BeforeEach
    void setUp() {
        service = new SignalIngestService(
                kafkaTemplate,
                idempotencyGuard,
                rateLimiter,
                new SimpleMeterRegistry(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("publishes accepted alerts keyed by tenant and service")
    void publishesAcceptedAlerts() {
        allowRateLimit();
        when(idempotencyGuard.claim(eq(TENANT), anyString())).thenReturn(true);
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(completedSend());

        IngestResponse response = service.ingest(TENANT, List.of(alert("evt-1"), alert("evt-2")));

        assertThat(response.accepted()).isEqualTo(2);
        assertThat(response.duplicates()).isZero();
        verify(kafkaTemplate, times(2)).send(eq(Topics.SIGNALS_ALERTS), eq("acme:checkout-api"), any());
    }

    @Test
    @DisplayName("a replayed event id is reported as duplicate and never reaches Kafka")
    void suppressesReplays() {
        allowRateLimit();
        when(idempotencyGuard.claim(TENANT, "evt-1")).thenReturn(false);

        IngestResponse response = service.ingest(TENANT, List.of(alert("evt-1")));

        assertThat(response.duplicates()).isEqualTo(1);
        assertThat(response.items().get(0).outcome()).isEqualTo(IngestResponse.Outcome.DUPLICATE);
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a failed publish releases the idempotency claim so the client can retry")
    void releasesClaimOnPublishFailure() {
        allowRateLimit();
        when(idempotencyGuard.claim(eq(TENANT), anyString())).thenReturn(true);
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(failedSend());

        IngestResponse response = service.ingest(TENANT, List.of(alert("evt-1")));

        assertThat(response.rejected()).isEqualTo(1);
        verify(idempotencyGuard).release(TENANT, "evt-1");
    }

    @Test
    @DisplayName("an exhausted token bucket rejects the whole batch before touching Kafka")
    void rejectsWhenRateLimited() {
        when(rateLimiter.tryConsume(eq(TENANT), anyInt())).thenReturn(new IngestRateLimiter.Decision(false, 0));

        assertThatThrownBy(() -> service.ingest(TENANT, List.of(alert("evt-1"))))
                .isInstanceOf(SignalIngestService.RateLimitExceededException.class);

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    private void allowRateLimit() {
        when(rateLimiter.tryConsume(eq(TENANT), anyInt())).thenReturn(new IngestRateLimiter.Decision(true, 4999));
    }

    private CompletableFuture<SendResult<String, Object>> completedSend() {
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<SendResult<String, Object>> failedSend() {
        return CompletableFuture.failedFuture(new IllegalStateException("broker unavailable"));
    }

    private AlertRequest alert(String eventId) {
        return new AlertRequest(
                eventId,
                "checkout-api",
                NOW,
                Map.of("region", "us-east-1"),
                "HighErrorRate",
                Severity.HIGH,
                "5xx rate above 2%",
                null,
                "prometheus",
                false,
                Map.of());
    }
}
