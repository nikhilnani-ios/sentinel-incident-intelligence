package io.sentinel.incident.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.sentinel.platform.common.event.Severity;
import io.sentinel.platform.domain.model.EscalationPolicy;
import io.sentinel.platform.domain.model.EscalationStep;
import io.sentinel.platform.domain.model.Incident;
import io.sentinel.platform.domain.repository.EscalationPolicyRepository;
import io.sentinel.platform.domain.repository.IncidentRepository;
import io.sentinel.platform.domain.repository.TimelineEntryRepository;

@ExtendWith(MockitoExtension.class)
class EscalationServiceTest {

    private static final Instant DETECTED_AT = Instant.parse("2026-03-01T12:00:00Z");

    @Mock
    private IncidentRepository incidentRepository;

    @Mock
    private EscalationPolicyRepository policyRepository;

    @Mock
    private TimelineEntryRepository timelineRepository;

    @Mock
    private IncidentEventBroadcaster broadcaster;

    @Mock
    private Notifier notifier;

    @Test
    @DisplayName("does not escalate before the first step's delay has elapsed")
    void waitsForTheFirstDelay() {
        Incident incident = criticalIncident();
        EscalationService service = serviceAt(DETECTED_AT.plus(Duration.ofMinutes(3)));
        stubPolicy();

        assertThat(service.escalateIfDue(incident)).isFalse();
        assertThat(incident.getEscalationLevel()).isZero();
        verify(notifier, never()).notify(any(), any());
    }

    @Test
    @DisplayName("escalates to the first target once the delay passes")
    void escalatesAfterFirstDelay() {
        Incident incident = criticalIncident();
        EscalationService service = serviceAt(DETECTED_AT.plus(Duration.ofMinutes(6)));
        stubPolicy();

        assertThat(service.escalateIfDue(incident)).isTrue();
        assertThat(incident.getEscalationLevel()).isEqualTo(1);
        verify(notifier).notify(any(), any());
        verify(timelineRepository).save(any());
    }

    @Test
    @DisplayName("later rungs wait for the cumulative delay, not just their own")
    void secondStepWaitsForCumulativeDelay() {
        Incident incident = criticalIncident();
        incident.escalate(); // already at level 1
        stubPolicy();

        // Step 0 waits 5 min, step 1 waits a further 10 — so level 2 is due at 15 minutes.
        assertThat(serviceAt(DETECTED_AT.plus(Duration.ofMinutes(12))).escalateIfDue(incident))
                .isFalse();
        assertThat(serviceAt(DETECTED_AT.plus(Duration.ofMinutes(16))).escalateIfDue(incident))
                .isTrue();
    }

    @Test
    @DisplayName("a policy that only covers HIGH and above ignores a MEDIUM incident")
    void respectsMinimumSeverity() {
        Incident incident =
                new Incident("acme", "INC-2", "search-api slow", Severity.MEDIUM, "search-api", DETECTED_AT);
        incident.setId(UUID.randomUUID());
        incident.setEscalationPolicyKey("default-critical");
        stubPolicy();

        assertThat(serviceAt(DETECTED_AT.plus(Duration.ofHours(2))).escalateIfDue(incident))
                .isFalse();
    }

    @Test
    @DisplayName("an exhausted ladder stops escalating instead of looping")
    void stopsAtTheEndOfTheLadder() {
        Incident incident = criticalIncident();
        incident.escalate();
        incident.escalate(); // level 2, beyond the two-step policy
        stubPolicy();

        assertThat(serviceAt(DETECTED_AT.plus(Duration.ofHours(4))).escalateIfDue(incident))
                .isFalse();
    }

    private void stubPolicy() {
        EscalationPolicy policy = new EscalationPolicy("acme", "default-critical", "Default ladder", Severity.HIGH);
        policy.addStep(new EscalationStep(EscalationStep.TargetType.TEAM, "primary-oncall", Duration.ofMinutes(5)));
        policy.addStep(new EscalationStep(EscalationStep.TargetType.TEAM, "secondary-oncall", Duration.ofMinutes(10)));

        when(policyRepository.findByTenantIdAndPolicyKey("acme", "default-critical"))
                .thenReturn(Optional.of(policy));
    }

    private EscalationService serviceAt(Instant now) {
        return new EscalationService(
                incidentRepository,
                policyRepository,
                timelineRepository,
                broadcaster,
                notifier,
                new SimpleMeterRegistry(),
                Clock.fixed(now, ZoneOffset.UTC),
                50);
    }

    private Incident criticalIncident() {
        Incident incident = new Incident(
                "acme", "INC-1", "orders-postgres down", Severity.CRITICAL, "orders-postgres", DETECTED_AT);
        incident.setId(UUID.randomUUID());
        incident.setEscalationPolicyKey("default-critical");
        return incident;
    }
}
