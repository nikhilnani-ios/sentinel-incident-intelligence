package io.sentinel.platform.common.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import io.sentinel.platform.common.error.DomainException;

/** Static accessor for the caller, for the handful of places where injection is impractical. */
public final class CurrentUser {

    private CurrentUser() {}

    public static AuthenticatedUser require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new UnauthenticatedException();
        }
        return user;
    }

    public static String requireTenantId() {
        return require().tenantId();
    }

    public static class UnauthenticatedException extends DomainException {
        public UnauthenticatedException() {
            super(HttpStatus.UNAUTHORIZED, "unauthenticated", "A valid bearer token is required");
        }
    }
}
