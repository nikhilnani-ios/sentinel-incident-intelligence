package io.sentinel.platform.common.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Type-specific body of a {@link SignalEnvelope}.
 *
 * <p>Sealed so that every consumer switch is exhaustive at compile time: adding a fifth signal
 * family breaks the build in every place that needs to handle it, which is exactly what we want.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
    @JsonSubTypes.Type(value = AlertPayload.class, name = "ALERT"),
    @JsonSubTypes.Type(value = MetricPayload.class, name = "METRIC"),
    @JsonSubTypes.Type(value = LogPayload.class, name = "LOG"),
    @JsonSubTypes.Type(value = DeploymentPayload.class, name = "DEPLOYMENT")
})
public sealed interface SignalPayload permits AlertPayload, MetricPayload, LogPayload, DeploymentPayload {

    SignalType type();

    /** Short, human-readable line used in incident timelines and LLM prompts. */
    String summary();
}
