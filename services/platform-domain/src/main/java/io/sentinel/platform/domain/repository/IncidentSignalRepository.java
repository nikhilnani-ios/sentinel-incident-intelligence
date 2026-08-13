package io.sentinel.platform.domain.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import io.sentinel.platform.domain.model.IncidentSignal;

public interface IncidentSignalRepository extends JpaRepository<IncidentSignal, UUID> {

    List<IncidentSignal> findByIncidentIdOrderByOccurredAtAsc(UUID incidentId);

    @Query(
            """
            select s from IncidentSignal s
            where s.incident.id = :incidentId and s.fingerprint = :fingerprint
            """)
    Optional<IncidentSignal> findByIncidentAndFingerprint(
            @Param("incidentId") UUID incidentId, @Param("fingerprint") String fingerprint);

    /** Most recent open incident carrying this fingerprint — the fast path for repeat alerts. */
    @Query(
            """
            select s.incident.id from IncidentSignal s
            where s.fingerprint = :fingerprint
              and s.incident.tenantId = :tenantId
              and s.incident.status <> io.sentinel.platform.common.event.IncidentStatus.RESOLVED
            order by s.lastSeenAt desc
            limit 1
            """)
    Optional<UUID> findOpenIncidentIdByFingerprint(
            @Param("tenantId") String tenantId, @Param("fingerprint") String fingerprint);
}
