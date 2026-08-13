package io.sentinel.platform.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import io.sentinel.platform.domain.model.Incident;
import io.sentinel.platform.domain.projection.ReliabilityMetrics;

/**
 * Read-only aggregate queries backing the reliability dashboards.
 *
 * <p>Native SQL on purpose: {@code date_trunc} and {@code percentile_cont} have no JPQL equivalent,
 * and these are exactly the queries where hand-written SQL earns its keep.
 */
public interface AnalyticsRepository extends JpaRepository<Incident, UUID> {

    @Query(
            value =
                    """
                    select to_char(date_trunc(:granularity, detected_at), 'YYYY-MM-DD') as bucket,
                           count(*) as incidentCount,
                           avg(extract(epoch from (acknowledged_at - detected_at)))
                               filter (where acknowledged_at is not null) as meanTimeToAcknowledgeSeconds,
                           avg(extract(epoch from (resolved_at - detected_at)))
                               filter (where resolved_at is not null) as meanTimeToResolveSeconds,
                           count(*) filter (where severity = 'CRITICAL') as criticalCount
                    from incident
                    where tenant_id = :tenantId
                      and detected_at between :from and :to
                    group by 1
                    order by 1
                    """,
            nativeQuery = true)
    List<ReliabilityMetrics> aggregateOverTime(
            @Param("tenantId") String tenantId,
            @Param("granularity") String granularity,
            @Param("from") Instant from,
            @Param("to") Instant to);

    @Query(
            value =
                    """
                    select primary_service_key as bucket,
                           count(*) as incidentCount,
                           avg(extract(epoch from (acknowledged_at - detected_at)))
                               filter (where acknowledged_at is not null) as meanTimeToAcknowledgeSeconds,
                           avg(extract(epoch from (resolved_at - detected_at)))
                               filter (where resolved_at is not null) as meanTimeToResolveSeconds,
                           count(*) filter (where severity = 'CRITICAL') as criticalCount
                    from incident
                    where tenant_id = :tenantId
                      and detected_at between :from and :to
                    group by 1
                    order by 2 desc
                    limit 15
                    """,
            nativeQuery = true)
    List<ReliabilityMetrics> aggregateByService(
            @Param("tenantId") String tenantId, @Param("from") Instant from, @Param("to") Instant to);

    /** p50/p90 resolution time in seconds, returned as a two-element array. */
    @Query(
            value =
                    """
                    select percentile_cont(0.5) within group (
                               order by extract(epoch from (resolved_at - detected_at))),
                           percentile_cont(0.9) within group (
                               order by extract(epoch from (resolved_at - detected_at)))
                    from incident
                    where tenant_id = :tenantId
                      and resolved_at is not null
                      and detected_at between :from and :to
                    """,
            nativeQuery = true)
    List<Double[]> resolutionPercentiles(
            @Param("tenantId") String tenantId, @Param("from") Instant from, @Param("to") Instant to);
}
