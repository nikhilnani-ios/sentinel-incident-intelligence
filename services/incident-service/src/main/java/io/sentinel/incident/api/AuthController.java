package io.sentinel.incident.api;

import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.sentinel.platform.common.security.AuthenticatedUser;
import io.sentinel.platform.common.security.JwtService;
import io.sentinel.platform.common.security.Role;

/**
 * Development-only token endpoint.
 *
 * <p>Restricted to the {@code local} and {@code demo} profiles: it hands out a signed token for any
 * email with any role, which is exactly what you want when demoing RBAC and exactly what must never
 * exist in production. Real deployments federate to an identity provider and this bean is simply
 * absent from the context.
 */
@RestController
@RequestMapping("/v1/auth")
@Profile({"local", "demo"})
public class AuthController {

    private final JwtService jwtService;

    public AuthController(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @PostMapping("/token")
    public TokenResponse issueToken(@Valid @RequestBody TokenRequest request) {
        AuthenticatedUser user =
                new AuthenticatedUser(request.email(), request.email(), request.tenantId(), Set.of(request.role()));
        return new TokenResponse(jwtService.issue(user), request.role(), request.tenantId());
    }

    public record TokenRequest(@NotBlank @Email String email, @NotBlank String tenantId, Role role) {
        public TokenRequest {
            role = role == null ? Role.VIEWER : role;
        }
    }

    public record TokenResponse(String accessToken, Role role, String tenantId) {}
}
