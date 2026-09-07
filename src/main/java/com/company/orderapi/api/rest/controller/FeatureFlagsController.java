package com.company.orderapi.api.rest.controller;

import com.company.orderapi.config.FeatureFlags;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * PR #34 - exposes the current feature-flag state so operators and tests can
 * see exactly which capabilities are switched on in this environment.
 */
@RestController
@RequestMapping("/api/v1/features")
public class FeatureFlagsController {

    private final FeatureFlags flags;

    public FeatureFlagsController(FeatureFlags flags) {
        this.flags = flags;
    }

    @GetMapping
    public Map<String, Boolean> list() {
        return Map.of(
                "csvExport", flags.isCsvExport(),
                "reporting", flags.isReporting());
    }
}
