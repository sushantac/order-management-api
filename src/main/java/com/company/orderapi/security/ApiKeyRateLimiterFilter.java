package com.company.orderapi.security;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * PR #29 - rate limiting PER API KEY (Resilience4j).
 *
 * <p>When a request carries the {@code X-API-Key} header, a permit is taken
 * from a RateLimiter bucketed by that key, configured from the {@code apiKey}
 * template instance (application.yml: 1000 permits/minute). Distinct keys get
 * distinct buckets, so one noisy client can never exhaust another client's
 * quota. Exceeding the limit answers 429 with the standard headers:
 * <ul>
 *   <li>{@code X-RateLimit-Remaining} - permits left in the current window;</li>
 *   <li>{@code X-RateLimit-Reset}     - epoch-seconds when the window refills;</li>
 *   <li>{@code Retry-After}           - seconds to wait on a 429.</li>
 * </ul>
 * Bearer-JWT callers are authenticated by scope and are NOT limited here
 * (scope-level quotas belong to the gateway/plan layer - out of learning scope).
 */
@Component
public class ApiKeyRateLimiterFilter extends OncePerRequestFilter {

    public static final String RATE_LIMITER_NAME = "apiKey";
    public static final String API_KEY_HEADER = "X-API-Key";

    private final RateLimiterRegistry rateLimiterRegistry;
    private final RateLimiterConfig template;

    public ApiKeyRateLimiterFilter(RateLimiterRegistry rateLimiterRegistry) {
        this.rateLimiterRegistry = rateLimiterRegistry;
        this.template = rateLimiterRegistry.rateLimiter(RATE_LIMITER_NAME)
                .getRateLimiterConfig();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        String apiKey = request.getHeader(API_KEY_HEADER);
        if (uri == null || !uri.startsWith("/api/") || apiKey == null || apiKey.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        // One bucket per key (instances are created on demand and reused).
        RateLimiter limiter = rateLimiterRegistry.rateLimiter(bucketName(apiKey), template);
        if (!limiter.acquirePermission()) {
            writeRateLimited(response, limiter);
            return;
        }
        response.setHeader("X-RateLimit-Remaining",
                String.valueOf(limiter.getMetrics().getAvailablePermissions()));
        response.setHeader("X-RateLimit-Reset", String.valueOf(resetEpochSeconds(limiter)));
        filterChain.doFilter(request, response);
    }

    private String bucketName(String apiKey) {
        return "apiKey-" + Integer.toUnsignedString(apiKey.hashCode());
    }

    private void writeRateLimited(HttpServletResponse response, RateLimiter limiter)
            throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("X-RateLimit-Remaining", "0");
        response.setHeader("X-RateLimit-Reset", String.valueOf(resetEpochSeconds(limiter)));
        long retryAfterSeconds = Math.max(1,
                (limiter.getRateLimiterConfig().getLimitRefreshPeriod().toMillis() + 999) / 1000);
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"status\":429,\"code\":\"RATE_LIMITED\","
                + "\"hint\":\"Too many requests - retry after the rate-limit window.\"}");
    }

    /** Upper-bound estimate of the next window boundary (epoch seconds). */
    private long resetEpochSeconds(RateLimiter limiter) {
        Duration refreshPeriod = limiter.getRateLimiterConfig().getLimitRefreshPeriod();
        return (System.currentTimeMillis() + refreshPeriod.toMillis()) / 1000;
    }
}
