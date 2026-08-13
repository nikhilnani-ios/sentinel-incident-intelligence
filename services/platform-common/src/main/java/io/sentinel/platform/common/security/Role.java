package io.sentinel.platform.common.security;

/**
 * Platform roles, ordered from least to most privileged.
 *
 * <p>The hierarchy is declared once in {@code SecurityConfig} so that {@code @PreAuthorize} checks
 * only ever name the minimum role required — an ADMIN never needs to be listed alongside RESPONDER.
 */
public enum Role {
    /** Read incidents and dashboards. The default for everyone in the org. */
    VIEWER,
    /** On-call engineers: acknowledge, comment, attach signals, request analysis. */
    RESPONDER,
    /** Incident commanders: resolve, escalate, publish postmortems. */
    COMMANDER,
    /** Platform owners: service catalog, dependency edges, escalation policies. */
    ADMIN;

    public String authority() {
        return "ROLE_" + name();
    }
}
