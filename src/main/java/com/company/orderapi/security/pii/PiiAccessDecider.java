package com.company.orderapi.security.pii;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * PR #27 - who may see raw PII in API responses.
 *
 * <p>Rule: mask whenever the caller is an authenticated principal WITHOUT full
 * PII access. Full access means the OAuth2 {@code pii_read} scope (a stronger
 * scope than the everyday {@code order_read}) or the trusted machine-client API
 * key. An anonymous/absent context means "security is disabled" (unit tests and
 * the pre-security integration suites) - those keep the historical full view.
 */
public final class PiiAccessDecider {

    private PiiAccessDecider() {
        // static utility
    }

    public static boolean shouldMask() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || auth instanceof AnonymousAuthenticationToken) {
            return false; // security disabled / test context -> historical full view
        }
        for (GrantedAuthority authority : auth.getAuthorities()) {
            String value = authority.getAuthority();
            if ("SCOPE_pii_read".equals(value) || "ROLE_API_KEY".equals(value)) {
                return false;
            }
        }
        return true;
    }
}
