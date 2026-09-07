package com.company.orderapi.security.pii;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PR #27 - pure masking helpers. Deterministic on purpose so tests can pin the
 * exact output shape; '*' hides the middle of a value while a hint of the
 * prefix/suffix survives for support staff to recognise the record.
 */
public final class PiiMasker {

    private PiiMasker() {
        // static utility
    }

    /** JSON keys treated as personal data, with the mask rule per key. */
    static final Map<String, PiiType> PII_KEYS = Map.of(
            "email", PiiType.EMAIL,
            "customerEmail", PiiType.EMAIL,
            "phoneNumber", PiiType.PHONE,
            "fullName", PiiType.NAME,
            "street", PiiType.NAME,
            "city", PiiType.NAME,
            "state", PiiType.NAME,
            "country", PiiType.NAME,
            "postalCode", PiiType.NAME);

    /** Matches any JSON string value; group(1)=key, group(2)=value body. */
    private static final Pattern JSON_KEY_VALUE = Pattern.compile(
            "\"([A-Za-z][A-Za-z0-9_]*)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** Last-resort sweep: any e-mail-looking token, under ANY key. */
    private static final Pattern GENERIC_EMAIL = Pattern.compile(
            "(?i)[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    public static String mask(String value, PiiType type) {
        if (value == null) {
            return null;
        }
        return switch (type) {
            case EMAIL -> maskEmail(value);
            case PHONE -> maskPhone(value);
            case NAME -> maskName(value);
        };
    }

    /** alice@example.com -> "a***@e***.com" (TLD kept, length hidden). */
    public static String maskEmail(String value) {
        if (value == null) {
            return null;
        }
        int at = value.lastIndexOf('@');
        if (at <= 0 || at == value.length() - 1) {
            return maskName(value);
        }
        String localHead = value.substring(0, 1);
        String domain = value.substring(at + 1);
        int dot = domain.indexOf('.');
        String domainTail = dot > 0 ? domain.substring(dot) : "";
        return localHead + "***@" + domain.substring(0, 1) + "***" + domainTail;
    }

    /** "+61 411 111 111" -> "+61***11" (country prefix + last two survive). */
    public static String maskPhone(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() <= 4) {
            return "***";
        }
        return trimmed.substring(0, 3) + "***" + trimmed.substring(trimmed.length() - 2);
    }

    /** "Alice Smith" -> "A***h" (initial + last char survive). */
    public static String maskName(String value) {
        if (value == null) {
            return null;
        }
        if (value.isEmpty()) {
            return "";
        }
        if (value.length() == 1) {
            return value + "***";
        }
        return value.substring(0, 1) + "***" + value.substring(value.length() - 1);
    }

    /**
     * Redacts PII from a JSON document (e.g. a request/response body heading
     * for the logs). Known personal-data keys are masked with their rule; a
     * generic e-mail sweep catches values under unexpected keys too.
     */
    public static String redactJson(String json) {
        if (json == null || json.isBlank()) {
            return json;
        }
        StringBuffer out = new StringBuffer();
        Matcher matcher = JSON_KEY_VALUE.matcher(json);
        while (matcher.find()) {
            PiiType type = PII_KEYS.get(matcher.group(1));
            if (type == null) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group(0)));
            } else {
                String masked = mask(matcher.group(2), type);
                matcher.appendReplacement(out, Matcher.quoteReplacement(
                        "\"" + matcher.group(1) + "\":\"" + masked + "\""));
            }
        }
        matcher.appendTail(out);
        return GENERIC_EMAIL.matcher(out).replaceAll("[EMAIL_REDACTED]");
    }
}
