package io.sentinel.platform.common.security;

import java.util.Set;

/**
 * The caller, as resolved from the bearer token.
 *
 * @param tenantId every query is scoped by this; it is never accepted from a request parameter
 */
public record AuthenticatedUser(String userId, String email, String tenantId, Set<Role> roles) {

    public AuthenticatedUser {
        roles = roles == null ? Set.of(Role.VIEWER) : Set.copyOf(roles);
    }

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }

    public String displayName() {
        return email == null ? userId : email;
    }
}
