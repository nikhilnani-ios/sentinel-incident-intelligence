package io.sentinel.incident.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Command bodies. Each carries a note so the timeline records why, not just what. */
public final class IncidentRequests {

    private IncidentRequests() {}

    public record Acknowledge(@Size(max = 500) String note) {}

    public record Resolve(
            @NotBlank @Size(max = 2000) String resolutionSummary, @Size(max = 100) String resolutionCategory) {}

    public record Comment(@NotBlank @Size(max = 2000) String message) {}

    public record Escalate(@Size(max = 500) String reason) {}

    public record MarkDuplicate(@NotBlank String duplicateOfIncidentKey) {}
}
