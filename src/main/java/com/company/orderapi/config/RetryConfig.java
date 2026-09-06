package com.company.orderapi.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;

/**
 * PR #8 - enables Spring Retry's {@code @Retryable}/{@code @Backoff}
 * processing (annotation-driven AOP).
 *
 * <p>Retry is used where transient failures are EXPECTED and self-healing:
 * optimistic-lock conflicts on hot rows are exactly that - the fix is simply
 * "read the newest version and try again". See {@code ProductStockService}.
 */
@Configuration
@EnableRetry
public class RetryConfig {
}
