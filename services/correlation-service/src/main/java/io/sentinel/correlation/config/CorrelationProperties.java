package io.sentinel.correlation.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Every tunable in the correlation pipeline, in one place.
 *
 * <p>These are the numbers an SRE team argues about after a bad week. Keeping them in configuration
 * rather than constants means tuning correlation is a config rollout, not a code change.
 *
 * @param dedupWindow how long a fingerprint is suppressed after firing
 * @param correlationWindow how far back to look for an incident to attach a signal to
 * @param maxGraphDepth hops to traverse when computing blast radius
 * @param attachThreshold minimum score for a signal to join an existing incident
 * @param deploymentLookback how far before detection a deploy is still considered suspicious
 * @param weights relative influence of each correlation factor; normalised at use
 */
@ConfigurationProperties(prefix = "sentinel.correlation")
public record CorrelationProperties(
        @DefaultValue("PT5M") Duration dedupWindow,
        @DefaultValue("PT15M") Duration correlationWindow,
        @DefaultValue("3") int maxGraphDepth,
        @DefaultValue("0.55") double attachThreshold,
        @DefaultValue("PT1H") Duration deploymentLookback,
        @DefaultValue("PT5M") Duration graphCacheTtl,
        @DefaultValue Weights weights) {

    /**
     * @param graphProximity how much shared topology matters
     * @param timeProximity how much "happened at the same moment" matters
     * @param severityAffinity how much matching urgency matters
     * @param labelOverlap how much shared labels (region, cluster, version) matter
     */
    public record Weights(
            @DefaultValue("0.45") double graphProximity,
            @DefaultValue("0.30") double timeProximity,
            @DefaultValue("0.10") double severityAffinity,
            @DefaultValue("0.15") double labelOverlap) {

        public double total() {
            return graphProximity + timeProximity + severityAffinity + labelOverlap;
        }
    }
}
