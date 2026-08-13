package io.sentinel.platform.common.security;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the bearer token into a Spring {@code Authentication}.
 *
 * <p>Also pushes tenant and user onto the MDC so every log line downstream is attributable without
 * threading context objects through call sites.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String MDC_TENANT = "tenantId";
    private static final String MDC_USER = "userId";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        extractToken(request).flatMap(jwtService::verify).ifPresent(user -> authenticate(user, request));

        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
            MDC.remove(MDC_TENANT);
            MDC.remove(MDC_USER);
        }
    }

    private void authenticate(AuthenticatedUser user, HttpServletRequest request) {
        List<SimpleGrantedAuthority> authorities = user.roles().stream()
                .map(role -> new SimpleGrantedAuthority(role.authority()))
                .toList();

        var authentication = new UsernamePasswordAuthenticationToken(user, null, authorities);
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        MDC.put(MDC_TENANT, user.tenantId());
        MDC.put(MDC_USER, user.userId());
    }

    private java.util.Optional<String> extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return java.util.Optional.of(
                    header.substring(BEARER_PREFIX.length()).trim());
        }
        // EventSource cannot set headers, so SSE endpoints accept the token as a query parameter.
        String queryToken = request.getParameter("access_token");
        return java.util.Optional.ofNullable(queryToken).filter(t -> !t.isBlank());
    }
}
