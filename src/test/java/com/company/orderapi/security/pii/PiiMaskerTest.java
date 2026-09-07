package com.company.orderapi.security.pii;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** PR #27 unit tests for the masking/redaction rules (pure, no Spring). */
class PiiMaskerTest {

    @Test
    void masksEmailKeepingTldHint() {
        assertThat(PiiMasker.maskEmail("alice@example.com"))
                .isEqualTo("a***@e***.com");
        assertThat(PiiMasker.maskEmail(null)).isNull();
    }

    @Test
    void masksPhoneKeepingPrefixAndSuffix() {
        assertThat(PiiMasker.maskPhone("+61 411 111 111")).isEqualTo("+61***11");
        assertThat(PiiMasker.maskPhone("0412345678")).isEqualTo("041***78");
    }

    @Test
    void masksNamesKeepingInitialsAndLastChar() {
        assertThat(PiiMasker.maskName("Alice Smith")).isEqualTo("A***h");
        assertThat(PiiMasker.mask("x", PiiType.EMAIL)) // no '@' -> name rule
                .isEqualTo("x***");
    }

    @Test
    void redactsKnownPiiKeysInJson() {
        String json = "{\"email\":\"alice@example.com\","
                + "\"fullName\":\"Alice Smith\","
                + "\"phoneNumber\":\"0412345678\","
                + "\"id\":7}";
        String redacted = PiiMasker.redactJson(json);
        assertThat(redacted)
                .contains("\"email\":\"a***@e***.com\"")
                .contains("\"fullName\":\"A***h\"")
                .contains("\"phoneNumber\":\"041***78\"")
                .contains("\"id\":7");
        assertThat(redacted).doesNotContain("alice@example.com");
    }

    @Test
    void sweepsUnknownEmailKeysAsFallback() {
        String json = "{\"billingContact\":\"bob@corp.com\"}";
        assertThat(PiiMasker.redactJson(json))
                .contains("[EMAIL_REDACTED]")
                .doesNotContain("bob@corp.com");
    }
}
