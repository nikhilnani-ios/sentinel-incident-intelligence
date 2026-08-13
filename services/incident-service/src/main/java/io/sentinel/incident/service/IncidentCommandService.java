package io.sentinel.incident.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.core.instrument.MeterRegistry;
import io.sentinel.platform.common.error.ResourceNotFoundException;
import io.sentinel.platform.common.event.IncidentEvent;
import io.sentinel.platform.common.security.AuthenticatedUser;
import io.sentinel.platform.domain.model.Incident;
import io.sentinel.platform.domain.model.TimelineEntry;
import io.sentinel.platform.domain.repository.IncidentRepository;
import io.sentinel.platform.domain.repository.TimelineEntryRepository;

/**
 * Write side of the incident API.
 *
 * <p>State machine enforcement lives on the {@link Incident} aggregate; this class owns the things
 * that surround a transition — the timeline entry, the fan-out event, and the MTTA/MTTR metrics.
 * Keeping those three together is what stops a future endpoint from mutating status and forgetting
 * to tell anyone.
 */
@Service
public class IncidentCommandService {

    private static final Logger log = LoggerFactory.getLogger(IncidentCommandService.class);

    private final IncidentRepository incidentRepository;
    private final TimelineEntryRepository timelineRepository;
    private final IncidentEventBroadcaster broadcaster;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public IncidentCommandService(
            IncidentRepository incidentRepository,
            TimelineEntryRepository timelineRepository,
            IncidentEventBroadcaster broadcaster,
            MeterRegistry meterRegistry,
            Clock clock) {
        this.incidentRepository = incidentRepository;
        this.timelineRepository = timelineRepository;
        this.broadcaster = broadcaster;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @Transactional
    public Incident acknowledge(AuthenticatedUser user, UUID incidentId, String note) {
        Incident incident = load(user.tenantId(), incidentId);
        Instant now = clock.instant();

        incident.acknowledge(user.userId(), now);
        record(
                incident,
                TimelineEntry.Kind.ACKNOWLEDGED,
                note == null || note.isBlank()
                        ? "%s acknowledged the incident".formatted(user.displayName())
                        : "%s acknowledged: %s".formatted(user.displayName(), note),
                user.displayName(),
                now);

        recordDuration("sentinel.incident.time_to_acknowledge", incident.timeToAcknowledge(), incident);
        publish(incident, IncidentEvent.Change.ACKNOWLEDGED, user.displayName());

        log.info("{} acknowledged by {}", incident.getIncidentKey(), user.displayName());
        return incident;
    }

    @Transactional
    public Incident resolve(AuthenticatedUser user, UUID incidentId, String resolutionSummary, String category) {
        Incident incident = load(user.tenantId(), incidentId);
        Instant now = clock.instant();

        incident.resolve(user.userId(), now);
        incident.setSummary(resolutionSummary);
        record(
                incident,
                TimelineEntry.Kind.RESOLVED,
                "%s resolved the incident: %s".formatted(user.displayName(), resolutionSummary),
                user.displayName(),
                now,
                Map.of("category", category == null ? "unspecified" : category));

        recordDuration("sentinel.incident.time_to_resolve", incident.timeToResolve(), incident);
        publish(incident, IncidentEvent.Change.RESOLVED, user.displayName());

        log.info("{} resolved by {} after {}", incident.getIncidentKey(), user.displayName(), incident.timeToResolve());
        return incident;
    }

    @Transactional
    public Incident mitigate(AuthenticatedUser user, UUID incidentId) {
        Incident incident = load(user.tenantId(), incidentId);
        Instant now = clock.instant();

        incident.mitigate(now);
        record(
                incident,
                TimelineEntry.Kind.MITIGATED,
                "%s marked customer impact as mitigated".formatted(user.displayName()),
                user.displayName(),
                now);
        publish(incident, IncidentEvent.Change.MITIGATED, user.displayName());
        return incident;
    }

    @Transactional
    public Incident comment(AuthenticatedUser user, UUID incidentId, String message) {
        Incident incident = load(user.tenantId(), incidentId);
        record(incident, TimelineEntry.Kind.COMMENT, message, user.displayName(), clock.instant());
        return incident;
    }

    @Transactional
    public Incident markDuplicate(AuthenticatedUser user, UUID incidentId, String duplicateOfKey) {
        Incident incident = load(user.tenantId(), incidentId);
        Incident original = incidentRepository
                .findByTenantIdAndIncidentKey(user.tenantId(), duplicateOfKey)
                .orElseThrow(() -> new ResourceNotFoundException("Incident", duplicateOfKey));

        Instant now = clock.instant();
        incident.markDuplicateOf(original.getIncidentKey());
        incident.resolve(user.userId(), now);
        record(
                incident,
                TimelineEntry.Kind.RESOLVED,
                "%s marked this as a duplicate of %s".formatted(user.displayName(), original.getIncidentKey()),
                user.displayName(),
                now,
                Map.of("duplicateOf", original.getIncidentKey()));

        publish(incident, IncidentEvent.Change.RESOLVED, user.displayName());
        return incident;
    }

    // ------------------------------------------------------------------ internals

    private Incident load(String tenantId, UUID incidentId) {
        return incidentRepository
                .findByTenantIdAndId(tenantId, incidentId)
                .orElseThrow(() -> new ResourceNotFoundException("Incident", incidentId));
    }

    private void record(Incident incident, TimelineEntry.Kind kind, String message, String actor, Instant at) {
        record(incident, kind, message, actor, at, Map.of());
    }

    private void record(
            Incident incident,
            TimelineEntry.Kind kind,
            String message,
            String actor,
            Instant at,
            Map<String, Object> metadata) {
        timelineRepository.save(new TimelineEntry(incident.getId(), kind, message, actor, at).withMetadata(metadata));
    }

    private void publish(Incident incident, IncidentEvent.Change change, String actor) {
        broadcaster.broadcast(incident, change, actor);
    }

    /**
     * MTTA and MTTR are recorded as timers rather than computed only in SQL, so Grafana can alert on
     * a regression the moment it happens instead of waiting for a dashboard query.
     */
    private void recordDuration(String metric, Duration duration, Incident incident) {
        if (duration == null) {
            return;
        }
        meterRegistry
                .timer(metric, "severity", incident.getSeverity().name(), "service", incident.getPrimaryServiceKey())
                .record(duration);
    }
}
