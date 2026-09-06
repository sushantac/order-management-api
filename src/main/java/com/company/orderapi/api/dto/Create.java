package com.company.orderapi.api.dto;

/**
 * PR #23 - validation group for CREATE operations. Combine with
 * {@code @Validated(Create.class)} on a controller parameter so only the
 * constraints tagged with this group run (e.g. stricter rules when creating).
 */
public interface Create {
}
