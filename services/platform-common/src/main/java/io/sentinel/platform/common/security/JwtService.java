package io.sentinel.platform.common.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.crypto.SecretKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Issues and verifies the platform's bearer tokens.
 *
 * <p>Symmetric HS256 is adequate here because every verifier is a first-party service inside the
 * mesh. The moment a third party needs to verify tokens this should move to RS256 with a published
 * JWKS endpoint, which is a drop-in change behind this class.
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final String CLAIM_TENANT = "tenant";
    private static final String CLAIM_ROLES = "roles";
    private static final String CLAIM_EMAIL = "email";

    private final SecretKey signingKey;
    private final Duration tokenTtl;
    private final String issuer;

    public JwtService(
            @Value("${sentinel.security.jwt-secret}") String secret,
            @Value("${sentinel.security.token-ttl:PT8H}") Duration tokenTtl,
            @Value("${sentinel.security.issuer:sentinel}") String issuer) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "sentinel.security.jwt-secret must be at least 32 bytes; refusing to start with a weak key");
        }
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.tokenTtl = tokenTtl;
        this.issuer = issuer;
    }

    public String issue(AuthenticatedUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(issuer)
                .subject(user.userId())
                .claim(CLAIM_TENANT, user.tenantId())
                .claim(CLAIM_EMAIL, user.email())
                .claim(CLAIM_ROLES, user.roles().stream().map(Enum::name).toList())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(tokenTtl)))
                .signWith(signingKey)
                .compact();
    }

    /** Returns empty rather than throwing: an invalid token is an expected condition, not an error. */
    public Optional<AuthenticatedUser> verify(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            return Optional.of(new AuthenticatedUser(
                    claims.getSubject(),
                    claims.get(CLAIM_EMAIL, String.class),
                    claims.get(CLAIM_TENANT, String.class),
                    parseRoles(claims)));
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Rejected bearer token: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private Set<Role> parseRoles(Claims claims) {
        List<String> raw = claims.get(CLAIM_ROLES, List.class);
        if (raw == null || raw.isEmpty()) {
            return Set.of(Role.VIEWER);
        }
        return raw.stream().map(Role::valueOf).collect(Collectors.toUnmodifiableSet());
    }
}
