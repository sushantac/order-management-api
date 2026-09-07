package com.company.orderapi.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * PR #31 - turns on @Scheduled (used by the outbox polling publisher).
 * The publisher bean itself only exists when app.kafka.enabled=true.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
