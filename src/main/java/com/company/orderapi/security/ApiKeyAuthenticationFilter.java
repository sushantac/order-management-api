package com.company.orderapi.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * PR #26 - lightweight API-key authentication for machine clients.
 *
 * <p>If the request carries a valid {@code X-API-Key}, a principal with the
 * {@code ROLE_API_KEY} authority is created so it can pass the same
 * {@code @PreAuthorize} rules used by scoped JWTs.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private final String expectedApiKey;

    public ApiKeyAuthenticationFilter(String expectedApiKey) {
        this.expectedApiKey = expectedApiKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        String provided = request.getHeader("X-API-Key");
        if (provided != null && provided.equals(expectedApiKey)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            var authentication = new UsernamePasswordAuthenticationToken(
                    "api-key-client", null,
                    List.of(new SimpleGrantedAuthority("ROLE_API_KEY")));
            authentication.setDetails(
                    new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        filterChain.doFilter(request, response);
    }
}
