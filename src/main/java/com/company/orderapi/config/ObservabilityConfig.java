package com.company.orderapi.config;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * PR #32 - activates Micrometer's {@code @Timed} annotation support and the
 * Prometheus registry (scraped at /actuator/prometheus).
 *
 * <p>Without the {@link TimedAspect} bean the annotation is inert; with it,
 * every invocation of an annotated method records a Timer
 * ({@code <name>_seconds} on the Prometheus endpoint) with tags for the class,
 * method and outcome.
 */
@Configuration
public class ObservabilityConfig {

    @Bean
    public TimedAspect timedAspect(MeterRegistry meterRegistry) {
        return new TimedAspect(meterRegistry);
    }

    /** The scrape registry Prometheus reads; Boot merges it into the composite. */
    @Bean
    public PrometheusMeterRegistry prometheusMeterRegistry() {
        return new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    }
}

