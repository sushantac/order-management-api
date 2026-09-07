package com.company.orderapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * PR #34 - FEATURE FLAGS bound from {@code app.features.*}.
 *
 * <p>A flag ships code in a disabled/gradual state and is switched via config
 * (per environment) - no redeploy, instant rollback. {@link #isCsvExport()} is
 * read by the export endpoint (PR #35); flags can later drive canary/progressive
 * rollouts.
 */
@Component
@ConfigurationProperties(prefix = "app.features")
public class FeatureFlags {

    /** Export endpoints (CSV/PDF) - see PR #35. */
    private boolean csvExport = true;

    /** Reporting dashboard (off until PR #35 decides otherwise). */
    private boolean reporting = false;

    public boolean isCsvExport() {
        return csvExport;
    }

    public void setCsvExport(boolean csvExport) {
        this.csvExport = csvExport;
    }

    public boolean isReporting() {
        return reporting;
    }

    public void setReporting(boolean reporting) {
        this.reporting = reporting;
    }
}
