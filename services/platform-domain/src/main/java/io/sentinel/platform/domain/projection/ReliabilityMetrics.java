package io.sentinel.platform.domain.projection;

/** Aggregate row returned by the analytics queries. */
public interface ReliabilityMetrics {

    String getBucket();

    long getIncidentCount();

    /** Mean time to acknowledge, seconds. Null when nothing in the bucket was acknowledged. */
    Double getMeanTimeToAcknowledgeSeconds();

    Double getMeanTimeToResolveSeconds();

    Long getCriticalCount();
}
