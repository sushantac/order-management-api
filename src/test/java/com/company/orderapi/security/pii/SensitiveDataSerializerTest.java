package com.company.orderapi.security.pii;

import com.company.orderapi.api.dto.CustomerResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #27 unit tests: the Jackson module swaps in the masking serializer, and
 * the serializer consults the security context (masked for non-privileged
 * callers, raw for pii_read / API-key / anonymous-test contexts).
 */
class SensitiveDataSerializerTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new PiiMaskingModule());

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void masksForAuthenticatedCallerWithoutPiiScope() throws Exception {
        authenticated("SCOPE_order_read");
        String json = mapper.writeValueAsString(customer());
        assertThat(json)
                .contains("\"email\":\"a***@e***.com\"")
                .contains("\"fullName\":\"A***h\"")
                .contains("\"phoneNumber\":\"041***78\"")
                .doesNotContain("alice@example.com")
                .doesNotContain("Alice Smith")
                .doesNotContain("0412345678");
    }

    @Test
    void showsRawForCallerWithPiiReadScope() throws Exception {
        authenticated("SCOPE_pii_read");
        String json = mapper.writeValueAsString(customer());
        assertThat(json).contains("\"email\":\"alice@example.com\"")
                .contains("\"fullName\":\"Alice Smith\"")
                .contains("\"phoneNumber\":\"0412345678\"");
    }

    @Test
    void showsRawForApiKeyPrincipal() throws Exception {
        authenticated("ROLE_API_KEY");
        String json = mapper.writeValueAsString(customer());
        assertThat(json).contains("\"email\":\"alice@example.com\"");
    }

    @Test
    void showsRawForAnonymousOrEmptyContext() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymousUser",
                        List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        String json = mapper.writeValueAsString(customer());
        assertThat(json).contains("\"email\":\"alice@example.com\"");

        SecurityContextHolder.clearContext();
        json = mapper.writeValueAsString(customer());
        assertThat(json).contains("\"email\":\"alice@example.com\"");
    }

    private void authenticated(String authority) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("tester", null,
                        List.of(new SimpleGrantedAuthority(authority))));
    }

    private CustomerResponse customer() {
        return new CustomerResponse(1L, "alice@example.com", "Alice Smith",
                "0412345678", null);
    }
}
