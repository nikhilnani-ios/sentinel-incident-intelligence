package io.sentinel.incident.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.sentinel.platform.domain.projection.ReliabilityMetrics;
import io.sentinel.platform.domain.repository.AnalyticsRepository;

/**
 * Reliability dashboards: MTTA, MTTR, incident frequency, worst offenders.
 *
 * <p>Cached in Redis for a few minutes. These are aggregate queries over the full incident table and
 * every dashboard refresh would otherwise run all of them; nobody makes a decision on the strength
 * of a five-minute-old MTTR, so the staleness is free.
 */
@Service
@Transactional(readOnly = true)
public class AnalyticsService {

    private final AnalyticsRepository analyticsRepository;

    public AnalyticsService(AnalyticsRepository analyticsRepository) {
        this.analyticsRepository = analyticsRepository;
    }

    @Cacheable(value = "reliability-overview", key = "#tenantId + ':' + #window.name()")
    public Overview overview(String tenantId, Window window) {
        Instant to = Instant.now();
        Instant from = to.minus(window.duration());

        List<ReliabilityMetrics> series =
                analyticsRepository.aggregateOverTime(tenantId, window.granularity(), from, to);
        List<ReliabilityMetrics> byService = analyticsRepository.aggregateByService(tenantId, from, to);
        Percentiles percentiles = percentiles(tenantId, from, to);

        long totalIncidents =
                series.stream().mapToLong(ReliabilityMetrics::getIncidentCount).sum();
        long criticalIncidents = series.stream()
                .map(ReliabilityMetrics::getCriticalCount)
                .filter(java.util.Objects::nonNull)
                .mapToLong(Long::longValue)
                .sum();

        return new Overview(
                window,
                from,
                to,
                totalIncidents,
                criticalIncidents,
                weightedMean(series, ReliabilityMetrics::getMeanTimeToAcknowledgeSeconds),
                weightedMean(series, ReliabilityMetrics::getMeanTimeToResolveSeconds),
                percentiles,
                series.stream().map(Bucket::from).toList(),
                byService.stream().map(Bucket::from).toList());
    }

    private Percentiles percentiles(String tenantId, Instant from, Instant to) {
        return analyticsRepository.resolutionPercentiles(tenantId, from, to).stream()
                .findFirst()
                .map(row -> new Percentiles(nullSafe(row[0]), nullSafe(row[1])))
                .orElse(new Percentiles(null, null));
    }

    private Long nullSafe(Double value) {
        return value == null ? null : value.longValue();
    }

    /**
     * Averages the per-bucket means weighted by incident count. Averaging the means directly would
     * let a quiet day with one slow incident distort the number as much as a busy day with fifty.
     */
    private Long weightedMean(
            List<ReliabilityMetrics> series, java.util.function.Function<ReliabilityMetrics, Double> extractor) {
        double weightedSum = 0;
        long weight = 0;

        for (ReliabilityMetrics metrics : series) {
            Double value = extractor.apply(metrics);
            if (value != null) {
                weightedSum += value * metrics.getIncidentCount();
                weight += metrics.getIncidentCount();
            }
        }
        return weight == 0 ? null : (long) (weightedSum / weight);
    }

    /** Reporting windows, each with the bucket granularity that keeps the chart readable. */
    public enum Window {
        LAST_24_HOURS(Duration.ofHours(24), "hour"),
        LAST_7_DAYS(Duration.ofDays(7), "day"),
        LAST_30_DAYS(Duration.ofDays(30), "day"),
        LAST_90_DAYS(Duration.ofDays(90), "week");

        private final Duration duration;
        private final String granularity;

        Window(Duration duration, String granularity) {
            this.duration = duration;
            this.granularity = granularity;
        }

        public Duration duration() {
            return duration;
        }

        public String granularity() {
            return granularity;
        }
    }

    public record Overview(
            Window window,
            Instant from,
            Instant to,
            long totalIncidents,
            long criticalIncidents,
            Long meanTimeToAcknowledgeSeconds,
            Long meanTimeToResolveSeconds,
            Percentiles resolutionPercentiles,
            List<Bucket> overTime,
            List<Bucket> byService) {}

    public record Percentiles(Long p50Seconds, Long p90Seconds) {}

    public record Bucket(
            String label,
            long incidentCount,
            Long meanTimeToAcknowledgeSeconds,
            Long meanTimeToResolveSeconds,
            long criticalCount) {

        static Bucket from(ReliabilityMetrics metrics) {
            return new Bucket(
                    metrics.getBucket(),
                    metrics.getIncidentCount(),
                    Optional.ofNullable(metrics.getMeanTimeToAcknowledgeSeconds())
                            .map(Double::longValue)
                            .orElse(null),
                    Optional.ofNullable(metrics.getMeanTimeToResolveSeconds())
                            .map(Double::longValue)
                            .orElse(null),
                    Optional.ofNullable(metrics.getCriticalCount()).orElse(0L));
        }
    }
}
