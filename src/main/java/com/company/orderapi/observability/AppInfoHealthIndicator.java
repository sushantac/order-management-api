package com.company.orderapi.observability;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * PR #32 - a CUSTOM health indicator.
 *
 * <p>Boot auto-discovers any {@link HealthIndicator} bean and merges it into
 * /actuator/health. This one surfaces the app identity + journey state - the
 * kind of detail a readiness check or ops dashboard greps for.
 */
@Component
public class AppInfoHealthIndicator implements HealthIndicator {

    @Override
    public Health health() {
        return Health.up()
                .withDetail("component", "order-management-api")
                .withDetail("journey", "PR #32 - observability")
                .build();
    }
}
