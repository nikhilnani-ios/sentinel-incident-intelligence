package io.sentinel.incident.api;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.sentinel.incident.api.dto.IncidentRequests;
import io.sentinel.incident.api.dto.IncidentResponses;
import io.sentinel.incident.service.IncidentCommandService;
import io.sentinel.incident.service.IncidentQueryService;
import io.sentinel.platform.common.event.IncidentStatus;
import io.sentinel.platform.common.event.Severity;
import io.sentinel.platform.common.security.AuthenticatedUser;
import io.sentinel.platform.common.security.CurrentUser;

/**
 * Incident read and command API.
 *
 * <p>Authorisation is per-endpoint and names the <em>minimum</em> role required — the role hierarchy
 * in {@code SecurityConfig} means COMMANDER and ADMIN inherit RESPONDER without being listed. The
 * split reflects how incident response actually works: anyone can look, on-call can acknowledge and
 * comment, and closing an incident or declaring it a duplicate is a commander's call.
 */
@RestController
@RequestMapping("/v1/incidents")
public class IncidentController {

    private static final int MAX_PAGE_SIZE = 100;

    private final IncidentQueryService queryService;
    private final IncidentCommandService commandService;

    public IncidentController(IncidentQueryService queryService, IncidentCommandService commandService) {
        this.queryService = queryService;
        this.commandService = commandService;
    }

    @GetMapping
    @PreAuthorize("hasRole('VIEWER')")
    public Page<IncidentResponses.Summary> list(
            @RequestParam(required = false) IncidentStatus status,
            @RequestParam(required = false) Severity severity,
            @RequestParam(required = false) String serviceKey,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {

        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(size, MAX_PAGE_SIZE));
        return queryService.search(CurrentUser.requireTenantId(), status, severity, serviceKey, pageable);
    }

    @GetMapping("/{incidentId}")
    @PreAuthorize("hasRole('VIEWER')")
    public IncidentResponses.Detail detail(@PathVariable UUID incidentId) {
        return queryService.detail(CurrentUser.requireTenantId(), incidentId);
    }

    @PostMapping("/{incidentId}/acknowledge")
    @PreAuthorize("hasRole('RESPONDER')")
    public IncidentResponses.Summary acknowledge(
            @PathVariable UUID incidentId, @Valid @RequestBody IncidentRequests.Acknowledge request) {
        AuthenticatedUser user = CurrentUser.require();
        return IncidentResponses.Summary.from(commandService.acknowledge(user, incidentId, request.note()));
    }

    @PostMapping("/{incidentId}/mitigate")
    @PreAuthorize("hasRole('RESPONDER')")
    public IncidentResponses.Summary mitigate(@PathVariable UUID incidentId) {
        return IncidentResponses.Summary.from(commandService.mitigate(CurrentUser.require(), incidentId));
    }

    @PostMapping("/{incidentId}/comments")
    @PreAuthorize("hasRole('RESPONDER')")
    public IncidentResponses.Summary comment(
            @PathVariable UUID incidentId, @Valid @RequestBody IncidentRequests.Comment request) {
        return IncidentResponses.Summary.from(
                commandService.comment(CurrentUser.require(), incidentId, request.message()));
    }

    @PostMapping("/{incidentId}/resolve")
    @PreAuthorize("hasRole('COMMANDER')")
    public IncidentResponses.Summary resolve(
            @PathVariable UUID incidentId, @Valid @RequestBody IncidentRequests.Resolve request) {
        return IncidentResponses.Summary.from(commandService.resolve(
                CurrentUser.require(), incidentId, request.resolutionSummary(), request.resolutionCategory()));
    }

    @PostMapping("/{incidentId}/duplicate")
    @PreAuthorize("hasRole('COMMANDER')")
    public IncidentResponses.Summary markDuplicate(
            @PathVariable UUID incidentId, @Valid @RequestBody IncidentRequests.MarkDuplicate request) {
        return IncidentResponses.Summary.from(
                commandService.markDuplicate(CurrentUser.require(), incidentId, request.duplicateOfIncidentKey()));
    }
}
