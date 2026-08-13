package io.sentinel.ingest.api;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.sentinel.ingest.api.dto.IngestRequests.AlertRequest;
import io.sentinel.ingest.api.dto.IngestRequests.BatchRequest;
import io.sentinel.ingest.api.dto.IngestRequests.DeploymentRequest;
import io.sentinel.ingest.api.dto.IngestRequests.LogRequest;
import io.sentinel.ingest.api.dto.IngestRequests.MetricRequest;
import io.sentinel.ingest.api.dto.IngestResponse;
import io.sentinel.ingest.service.SignalIngestService;
import io.sentinel.platform.common.security.CurrentUser;

/**
 * Signal intake.
 *
 * <p>Every endpoint accepts a batch and returns 202 with per-item outcomes rather than 201: we have
 * accepted responsibility for the signal, but correlation happens asynchronously, so promising a
 * created resource would be a lie.
 *
 * <p>The tenant is taken from the token, never from the body — otherwise any client could write
 * signals into another tenant's incident stream.
 */
@RestController
@RequestMapping("/v1/ingest")
public class IngestController {

    private final SignalIngestService ingestService;

    public IngestController(SignalIngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping("/alerts")
    public ResponseEntity<IngestResponse> ingestAlerts(@Valid @RequestBody BatchRequest<AlertRequest> request) {
        return accepted(ingestService.ingest(CurrentUser.requireTenantId(), request.items()));
    }

    @PostMapping("/metrics")
    public ResponseEntity<IngestResponse> ingestMetrics(@Valid @RequestBody BatchRequest<MetricRequest> request) {
        return accepted(ingestService.ingest(CurrentUser.requireTenantId(), request.items()));
    }

    @PostMapping("/logs")
    public ResponseEntity<IngestResponse> ingestLogs(@Valid @RequestBody BatchRequest<LogRequest> request) {
        return accepted(ingestService.ingest(CurrentUser.requireTenantId(), request.items()));
    }

    @PostMapping("/deployments")
    public ResponseEntity<IngestResponse> ingestDeployments(
            @Valid @RequestBody BatchRequest<DeploymentRequest> request) {
        return accepted(ingestService.ingest(CurrentUser.requireTenantId(), request.items()));
    }

    /** Single-item convenience endpoint for CI pipelines, which post one deploy at a time. */
    @PostMapping("/deployments/single")
    public ResponseEntity<IngestResponse> ingestDeployment(@Valid @RequestBody DeploymentRequest request) {
        return accepted(ingestService.ingest(CurrentUser.requireTenantId(), List.of(request)));
    }

    private ResponseEntity<IngestResponse> accepted(IngestResponse response) {
        HttpStatus status = response.rejected() > 0 ? HttpStatus.MULTI_STATUS : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(response);
    }
}
