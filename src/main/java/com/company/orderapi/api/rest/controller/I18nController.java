package com.company.orderapi.api.rest.controller;

import org.springframework.context.MessageSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.Map;

/**
 * PR #35 - resolves API messages by locale (Accept-Language), demonstrating the
 * i18n infrastructure: {@code GET /api/v1/messages/{key}?arg=...}.
 */
@RestController
@RequestMapping("/api/v1/messages")
public class I18nController {

    private final MessageSource messageSource;

    public I18nController(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @GetMapping("/{key}")
    public Map<String, String> message(
            @PathVariable String key,
            @RequestParam(required = false) String arg,
            @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        Locale resolved = locale == null ? Locale.getDefault() : locale;
        String message = messageSource.getMessage(
                key, arg == null ? null : new Object[]{arg}, key, resolved);
        return Map.of("key", key, "locale", resolved.getLanguage(), "message", message);
    }
}
