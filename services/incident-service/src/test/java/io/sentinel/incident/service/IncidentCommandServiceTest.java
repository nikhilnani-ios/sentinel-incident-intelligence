package io.sentinel.incident.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.sentinel.platform.common.error.InvalidStateTransitionException;
import io.sentinel.platform.common.error.ResourceNotFoundException;
import io.sentinel.platform.common.event.IncidentEvent;
import io.sentinel.platform.common.event.IncidentStatus;
import io.sentinel.platform.common.event.Severity;
import io.sentinel.platform.common.security.AuthenticatedUser;
import io.sentinel.platform.common.security.Role;
import io.sentinel.platform.domain.model.Incident;
import io.sentinel.platform.domain.model.TimelineEntry;
import io.sentinel.platform.domain.repository.IncidentRepository;
import io.sentinel.platform.domain.repository.TimelineEntryRepository;

@ExtendWith(MockitoExtension.class)
class IncidentCommandServiceTest {

    private static final Instant DETECTED_AT = Instant.parse("2026-03-01T12:00:00Z");
    private static final Instant NOW = DETECTED_AT.plus(Duration.ofMinutes(7));
    private static final AuthenticatedUser RESPONDER =
            new AuthenticatedUser("u-1", "sre@acme.io", "acme", Set.of(Role.COMMANDER));

    @Mock
    private IncidentRepository incidentRepository;

    @Mock
    private TimelineEntryRepository timelineRepository;

    @Mock
    private IncidentEventBroadcaster broadcaster;

    private SimpleMeterRegistry meterRegistry;
    private IncidentCommandService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new IncidentCommandService(
                incidentRepository, timelineRepository, broadcaster, meterRegistry, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("acknowledging records the actor, the timeline entry and the MTTA metric")
    void acknowledgeRecordsEverything() {
        Incident incident = openIncident();
        when(incidentRepository.findByTenantIdAndId("acme", incident.getId())).thenReturn(Optional.of(incident));

        service.acknowledge(RESPONDER, incident.getId(), "taking a look");

        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
        assertThat(incident.getAcknowledgedBy()).isEqualTo("u-1");
        assertThat(incident.timeToAcknowledge()).isEqualTo(Duration.ofMinutes(7));

        ArgumentCaptor<TimelineEntry> entry = ArgumentCaptor.forClass(TimelineEntry.class);
        verify(timelineRepository).save(entry.capture());
        assertThat(entry.getValue().getKind()).isEqualTo(TimelineEntry.Kind.ACKNOWLEDGED);
        assertThat(entry.getValue().getMessage()).contains("taking a look");

        verify(broadcaster).broadcast(eq(incident), eq(IncidentEvent.Change.ACKNOWLEDGED), any());
        assertThat(meterRegistry.find("sentinel.incident.time_to_acknowledge").timer())
                .isNotNull();
    }

    @Test
    @DisplayName("resolving straight from OPEN back-fills the acknowledgement")
    void resolveBackfillsAcknowledgement() {
        Incident incident = openIncident();
        when(incidentRepository.findByTenantIdAndId("acme", incident.getId())).thenReturn(Optional.of(incident));

        service.resolve(RESPONDER, incident.getId(), "rolled back v2.4.1", "bad-deploy");

        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(incident.getAcknowledgedAt()).isEqualTo(NOW);
        assertThat(incident.timeToResolve()).isEqualTo(Duration.ofMinutes(7));
        assertThat(incident.getSummary()).isEqualTo("rolled back v2.4.1");
    }

    @Test
    @DisplayName("a resolved incident cannot be acknowledged again")
    void rejectsIllegalTransition() {
        Incident incident = openIncident();
        incident.resolve("someone-else", DETECTED_AT.plusSeconds(60));
        when(incidentRepository.findByTenantIdAndId("acme", incident.getId())).thenReturn(Optional.of(incident));

        assertThatThrownBy(() -> service.acknowledge(RESPONDER, incident.getId(), null))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    @DisplayName("an incident from another tenant is simply not found")
    void enforcesTenantIsolation() {
        UUID unknownId = UUID.randomUUID();
        when(incidentRepository.findByTenantIdAndId("acme", unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.acknowledge(RESPONDER, unknownId, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private Incident openIncident() {
        Incident incident = new Incident(
                "acme", "INC-1001", "checkout-api: HighErrorRate", Severity.CRITICAL, "checkout-api", DETECTED_AT);
        incident.setId(UUID.randomUUID());
        return incident;
    }
}
