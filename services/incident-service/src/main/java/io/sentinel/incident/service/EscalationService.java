package io.sentinel.incident.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.sentinel.platform.common.event.IncidentEvent;
import io.sentinel.platform.domain.model.EscalationPolicy;
import io.sentinel.platform.domain.model.EscalationStep;
import io.sentinel.platform.domain.model.Incident;
import io.sentinel.platform.domain.model.TimelineEntry;
import io.sentinel.platform.domain.repository.EscalationPolicyRepository;
import io.sentinel.platform.domain.repository.IncidentRepository;
import io.sentinel.platform.domain.repository.TimelineEntryRepository;

/**
 * Advances unacknowledged incidents up their escalation ladder.
 *
 * <p>Runs as a poller rather than a per-incident scheduled task. A million in-flight timers is a
 * memory leak waiting to happen, and timers do not survive a restart — whereas a query against a
 * partial index on {@code (status='OPEN' and acknowledged_at is null)} is cheap and stateless,
 * so any replica can pick up the work at any time.
 *
 * <p>The claim query uses {@code FOR UPDATE SKIP LOCKED}, so every replica can run the sweep
 * concurrently and simply take a disjoint batch. No leader election, no distributed lock, and no
 * chance of paging the same person twice.
 */
@Service
public class EscalationService {

    private static final Logger log = LoggerFactory.getLogger(EscalationService.class);

    private final IncidentRepository incidentRepository;
    private final EscalationPolicyRepository policyRepository;
    private final TimelineEntryRepository timelineRepository;
    private final IncidentEventBroadcaster broadcaster;
    private final Notifier notifier;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final int batchSize;

    @SuppressWarnings("java:S107")
    public EscalationService(
            IncidentRepository incidentRepository,
            EscalationPolicyRepository policyRepository,
            TimelineEntryRepository timelineRepository,
            IncidentEventBroadcaster broadcaster,
            Notifier notifier,
            MeterRegistry meterRegistry,
            Clock clock,
            @Value("${sentinel.escalation.batch-size:50}") int batchSize) {
        this.incidentRepository = incidentRepository;
        this.policyRepository = policyRepository;
        this.timelineRepository = timelineRepository;
        this.broadcaster = broadcaster;
        this.notifier = notifier;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    /**
     * Sweeps for incidents that have gone unacknowledged past their current step's delay.
     *
     * <p>Interval is short relative to the shortest ladder step; escalation resolution is bounded by
     * the poll interval, so a 30s poll means a 5-minute step fires between 5:00 and 5:30.
     */
    @Scheduled(fixedDelayString = "${sentinel.escalation.poll-interval-ms:30000}")
    @Transactional
    public void sweep() {
        // Claim anything at least as old as the shortest possible first step, then filter precisely
        // in memory using each incident's own policy.
        Instant threshold = clock.instant().minus(Duration.ofMinutes(1));
        List<Incident> claimed = incidentRepository.claimUnacknowledged(threshold, batchSize);

        if (claimed.isEmpty()) {
            return;
        }

        int escalated = 0;
        for (Incident incident : claimed) {
            if (escalateIfDue(incident)) {
                escalated++;
            }
        }
        log.debug("Escalation sweep examined {} incidents, escalated {}", claimed.size(), escalated);
    }

    /** @return true when the incident was advanced a level */
    @Transactional
    public boolean escalateIfDue(Incident incident) {
        Optional<EscalationPolicy> maybePolicy = resolvePolicy(incident);
        if (maybePolicy.isEmpty()) {
            return false;
        }

        EscalationPolicy policy = maybePolicy.get();
        if (!policy.appliesTo(incident.getSeverity()) || policy.isExhausted(incident.getEscalationLevel())) {
            return false;
        }

        int nextLevel = incident.getEscalationLevel();
        Duration due = policy.delayBefore(nextLevel);
        Duration elapsed = Duration.between(incident.getDetectedAt(), clock.instant());

        // Each rung waits its own delay on top of everything before it, so a ladder of 5/10/15
        // minutes pages at 5, then 15, then 30 minutes after detection.
        Duration cumulative = cumulativeDelay(policy, nextLevel);
        if (elapsed.compareTo(cumulative) < 0) {
            return false;
        }

        EscalationStep step = policy.stepAt(nextLevel).orElseThrow();
        incident.escalate();
        incidentRepository.save(incident);

        notifier.notify(incident, step);
        timelineRepository.save(new TimelineEntry(
                        incident.getId(),
                        TimelineEntry.Kind.ESCALATED,
                        "Unacknowledged after %d min — escalated to %s (%s)"
                                .formatted(elapsed.toMinutes(), step.getTarget(), step.getTargetType()),
                        "escalation-policy:" + policy.getPolicyKey(),
                        clock.instant())
                .withMetadata(Map.of(
                        "level", nextLevel,
                        "target", step.getTarget(),
                        "targetType", step.getTargetType().name(),
                        "dueAfterSeconds", due.toSeconds())));

        broadcaster.broadcast(incident, IncidentEvent.Change.ESCALATED, "escalation-policy");
        Counter.builder("sentinel.escalation.triggered")
                .tag("level", String.valueOf(nextLevel))
                .tag("severity", incident.getSeverity().name())
                .register(meterRegistry)
                .increment();

        log.warn(
                "Escalated {} to level {} ({}) after {} minutes unacknowledged",
                incident.getIncidentKey(),
                nextLevel,
                step.getTarget(),
                elapsed.toMinutes());
        return true;
    }

    private Duration cumulativeDelay(EscalationPolicy policy, int upToLevel) {
        return policy.getSteps().stream()
                .filter(step -> step.getStepOrder() <= upToLevel)
                .map(EscalationStep::getDelay)
                .reduce(Duration.ZERO, Duration::plus);
    }

    private Optional<EscalationPolicy> resolvePolicy(Incident incident) {
        if (incident.getEscalationPolicyKey() == null) {
            return Optional.empty();
        }
        return policyRepository.findByTenantIdAndPolicyKey(incident.getTenantId(), incident.getEscalationPolicyKey());
    }
}
