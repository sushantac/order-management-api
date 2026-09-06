package com.company.orderapi.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * PR #21/#23 - customer request with VALIDATION GROUPS.
 *
 * <p>Rules split by group: {@code email} is mandatory in both Create and Update;
 * the name length check only applies when CREATING; the phone pattern is
 * optional but must be well-formed whenever present. Controllers select the
 * group with {@code @Validated(Create.class)} / {@code @Validated(Update.class)}.
 */
public record CustomerRequest(
        @NotBlank(groups = {Create.class, Update.class})
        @Email(groups = {Create.class, Update.class})
        String email,

        @NotBlank(groups = {Create.class, Update.class})
        @Size(min = 2, max = 100, groups = Create.class)
        String fullName,

        @Pattern(regexp = "^[0-9+ \\-]{0,20}$", groups = {Create.class, Update.class})
        String phoneNumber) {
}
