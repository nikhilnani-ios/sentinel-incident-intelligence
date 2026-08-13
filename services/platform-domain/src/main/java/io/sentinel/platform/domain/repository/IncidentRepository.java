package io.sentinel.platform.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import io.sentinel.platform.common.event.IncidentStatus;
import io.sentinel.platform.common.event.Severity;
import io.sentinel.platform.domain.model.Incident;

public interface IncidentRepository extends JpaRepository<Incident, UUID> {

    Optional<Incident> findByTenantIdAndId(String tenantId, UUID id);

    Optional<Incident> findByTenantIdAndIncidentKey(String tenantId, String incidentKey);

    @Query(
            """
            select i from Incident i
            where i.tenantId = :tenantId
              and (:status is null or i.status = :status)
              and (:severity is null or i.severity = :severity)
              and (:serviceKey is null or i.primaryServiceKey = :serviceKey
                   or i.affectedServiceKeys like concat('%', :serviceKey, '%'))
            order by i.detectedAt desc
            """)
    Page<Incident> search(
            @Param("tenantId") String tenantId,
            @Param("status") IncidentStatus status,
            @Param("severity") Severity severity,
            @Param("serviceKey") String serviceKey,
            Pageable pageable);

    /**
     * Candidate incidents for correlation: open, recent, and touching one of the services in the
     * blast radius. Deliberately narrow — this runs on every single signal.
     */
    @Query(
            """
            select i from Incident i
            where i.tenantId = :tenantId
              and i.status <> io.sentinel.platform.common.event.IncidentStatus.RESOLVED
              and i.detectedAt > :since
              and (i.primaryServiceKey in :serviceKeys
                   or exists (select 1 from IncidentSignal s
                              where s.incident = i and s.serviceKey in :serviceKeys))
            order by i.detectedAt desc
            """)
    List<Incident> findCorrelationCandidates(
            @Param("tenantId") String tenantId,
            @Param("serviceKeys") List<String> serviceKeys,
            @Param("since") Instant since);

    /**
     * Pessimistic read for the correlation write path. Two partitions can hold signals for services
     * that share an incident, so attaching without a lock risks a lost severity update.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Incident i where i.id = :id")
    Optional<Incident> lockById(@Param("id") UUID id);

    /**
     * Escalation sweep. {@code skip locked} lets every replica of the scheduler pull a disjoint
     * batch without a distributed lock or a leader election.
     */
    @Query(
            value =
                    """
                    select * from incident
                    where status = 'OPEN'
                      and acknowledged_at is null
                      and severity in ('CRITICAL', 'HIGH')
                      and detected_at < :threshold
                    order by detected_at
                    limit :batchSize
                    for update skip locked
                    """,
            nativeQuery = true)
    List<Incident> claimUnacknowledged(@Param("threshold") Instant threshold, @Param("batchSize") int batchSize);

    @Query(value = "select nextval('incident_key_seq')", nativeQuery = true)
    long nextIncidentNumber();

    long countByTenantIdAndDetectedAtBetween(String tenantId, Instant from, Instant to);
}
