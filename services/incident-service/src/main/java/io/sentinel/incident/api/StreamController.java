package io.sentinel.incident.api;

import java.util.Set;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.sentinel.incident.stream.IncidentStreamRegistry;
import io.sentinel.platform.common.security.AuthenticatedUser;
import io.sentinel.platform.common.security.CurrentUser;

/**
 * Live incident feed over Server-Sent Events.
 *
 * <p>The browser's {@code EventSource} cannot set an Authorization header, so the token is accepted
 * as a query parameter here — handled in {@code JwtAuthenticationFilter}. That is a real trade-off:
 * tokens in query strings end up in access logs. It is acceptable because these tokens are short
 * lived and scoped to one tenant, and the alternative (a cookie) would drag CSRF back into an
 * otherwise stateless API.
 */
@RestController
@RequestMapping("/v1/streams")
public class StreamController {

    private final IncidentStreamRegistry streamRegistry;

    public StreamController(IncidentStreamRegistry streamRegistry) {
        this.streamRegistry = streamRegistry;
    }

    @GetMapping(value = "/incidents", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasRole('VIEWER')")
    public SseEmitter streamIncidents(@RequestParam(required = false) Set<String> services) {
        AuthenticatedUser user = CurrentUser.require();
        return streamRegistry.subscribe(user.tenantId(), user.userId(), services == null ? Set.of() : services);
    }
}
