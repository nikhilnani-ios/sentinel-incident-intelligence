package io.sentinel.ingest.api;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.sentinel.ingest.api.dto.IngestRequests.AlertRequest;
import io.sentinel.ingest.api.dto.IngestResponse;
import io.sentinel.ingest.service.SignalIngestService;
import io.sentinel.platform.common.event.Severity;
import io.sentinel.platform.common.security.CurrentUser;

/**
 * Adapter for Prometheus Alertmanager's webhook format.
 *
 * <p>Kept apart from {@link IngestController} on purpose: vendor quirks belong in an adapter, not in
 * the platform's own contract. Adding Grafana or Datadog later means another controller here and no
 * change at all downstream.
 */
@RestController
@RequestMapping("/v1/webhooks/alertmanager")
public class AlertmanagerWebhookController {

    private static final Logger log = LoggerFactory.getLogger(AlertmanagerWebhookController.class);

    /** Alertmanager label carrying the service; configurable per install in a real deployment. */
    private static final String SERVICE_LABEL = "service";

    private final SignalIngestService ingestService;

    public AlertmanagerWebhookController(SignalIngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping
    public ResponseEntity<IngestResponse> receive(@RequestBody AlertmanagerPayload payload) {
        List<AlertRequest> requests = payload.alerts().stream()
                .map(alert -> toAlertRequest(payload.groupKey(), alert))
                .toList();

        log.debug("Accepted {} alerts from Alertmanager group {}", requests.size(), payload.groupKey());
        return ResponseEntity.accepted().body(ingestService.ingest(CurrentUser.requireTenantId(), requests));
    }

    private AlertRequest toAlertRequest(String groupKey, AlertmanagerAlert alert) {
        Map<String, String> labels = new HashMap<>(alert.labels());
        String serviceKey = labels.getOrDefault(SERVICE_LABEL, labels.getOrDefault("job", "unknown-service"));
        String alertName = labels.getOrDefault("alertname", "UnnamedAlert");
        boolean resolved = "resolved".equalsIgnoreCase(alert.status());

        // Alertmanager sends fingerprints; reusing one as the event id makes retries idempotent.
        String eventId = alert.fingerprint() == null
                ? groupKey + ':' + alertName + ':' + alert.startsAt()
                : alert.fingerprint() + ':' + alert.status();

        return new AlertRequest(
                eventId,
                serviceKey,
                alert.startsAt() == null ? Instant.now() : alert.startsAt(),
                labels,
                alertName,
                mapSeverity(labels.get("severity")),
                alert.annotations()
                        .getOrDefault("description", alert.annotations().get("summary")),
                alert.annotations().get("runbook_url"),
                "alertmanager",
                resolved,
                alert.annotations());
    }

    private Severity mapSeverity(String raw) {
        if (raw == null) {
            return Severity.MEDIUM;
        }
        return switch (raw.toLowerCase()) {
            case "critical", "page", "sev1" -> Severity.CRITICAL;
            case "warning", "high", "sev2" -> Severity.HIGH;
            case "info", "informational" -> Severity.INFO;
            case "low", "sev4" -> Severity.LOW;
            default -> Severity.MEDIUM;
        };
    }

    public record AlertmanagerPayload(String groupKey, String status, @NotNull List<AlertmanagerAlert> alerts) {
        public AlertmanagerPayload {
            alerts = alerts == null ? List.of() : alerts;
        }
    }

    public record AlertmanagerAlert(
            String status,
            String fingerprint,
            Instant startsAt,
            Instant endsAt,
            Map<String, String> labels,
            Map<String, String> annotations) {

        public AlertmanagerAlert {
            labels = labels == null ? Map.of() : labels;
            annotations = annotations == null ? Map.of() : annotations;
        }
    }
}
