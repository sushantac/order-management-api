package com.company.orderapi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.util.Optional;

/**
 * PR #10 - JPA auditing configuration.
 *
 * <p>{@code @EnableJpaAuditing} activates Spring Data's auditing support so the
 * {@code AuditingEntityListener} (registered on {@code BaseEntity}) fills the
 * {@code @CreatedDate/@LastModifiedDate/@CreatedBy/@LastModifiedBy} fields.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {

    /**
     * Supplies the CURRENT user for {@code createdBy}/{@code updatedBy}.
     *
     * <p>PR #26 (security) will read the authenticated principal from the
     * security context here. Until then every change is attributed to a
     * dedicated marker user, so nothing ever silently records a null auditor.
     */
    @Bean
    public AuditorAware<String> auditorAware() {
        return () -> Optional.of("system");
    }
}
