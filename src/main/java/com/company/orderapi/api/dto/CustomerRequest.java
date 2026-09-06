package com.company.orderapi.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * PR #21 - create/update customer request (Java record).
 *
 * <p>Records give immutable value carriers with equals/hashCode/toString for
 * free. Validation annotations travel WITH the contract (full validation
 * strategies arrive in PR #23).
 */
public record CustomerRequest(
        @NotBlank @Email String email,
        @NotBlank String fullName,
        String phoneNumber) {
}
