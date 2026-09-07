package com.company.orderapi.api.rest.controller;

import com.company.orderapi.api.dto.ErasureResponse;
import com.company.orderapi.api.dto.PortabilityResponse;
import com.company.orderapi.domain.service.GdprService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PR #27 - GDPR subject-rights endpoints. These sit NEXT TO the customer CRUD
 * on purpose: an erasure/export is not a regular resource mutation, it is a
 * compliance action - so it is declared, authorised and audited separately.
 *
 * <p>Both actions need the privileged {@code pii_*} OAuth2 scopes (or the
 * machine-client API key); everyday {@code order_read}/{@code order_write}
 * scopes deliberately CANNOT trigger them.
 */
@RestController
@RequestMapping("/api/v1/customers/{customerId}")
public class GdprController {

    private final GdprService gdpr;

    public GdprController(GdprService gdpr) {
        this.gdpr = gdpr;
    }

    @Operation(summary = "Right to erasure (GDPR Art. 17)",
            description = "Physically deletes the customer when there is no order "
                    + "history; otherwise anonymizes the personal fields (history is "
                    + "kept for legal retention). Every call is audit-logged.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Erasure handled (DELETED or ANONYMIZED)"),
            @ApiResponse(responseCode = "403", description = "Missing pii_write scope"),
            @ApiResponse(responseCode = "404", description = "Unknown customer")
    })
    @PreAuthorize("@securityProperties.enabled == false or "
            + "hasAnyAuthority('SCOPE_pii_write', 'ROLE_API_KEY')")
    @DeleteMapping("/data")
    public ResponseEntity<ErasureResponse> erase(@PathVariable Long customerId) {
        return ResponseEntity.ok(gdpr.erase(customerId));
    }

    @Operation(summary = "Data portability (GDPR Art. 20)",
            description = "Returns a machine-readable JSON export of the "
                    + "customer's profile, addresses and order history.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Portability export"),
            @ApiResponse(responseCode = "403", description = "Missing pii_read scope"),
            @ApiResponse(responseCode = "404", description = "Unknown customer")
    })
    @PreAuthorize("@securityProperties.enabled == false or "
            + "hasAnyAuthority('SCOPE_pii_read', 'ROLE_API_KEY')")
    @GetMapping("/portability")
    public PortabilityResponse portability(@PathVariable Long customerId) {
        return gdpr.exportData(customerId);
    }
}
