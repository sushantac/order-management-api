package com.company.orderapi.config;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

/**
 * PR #35 - internationalization (i18n) message source.
 *
 * <p>Messages live in {@code i18n/messages*.properties}; locale resolution is
 * delegated to Spring (Accept-Language header). UTF-8 throughout so accented
 * characters are never mangled.
 */
@Configuration
public class I18nConfig {

    @Bean
    public MessageSource messageSource() {
        ReloadableResourceBundleMessageSource source =
                new ReloadableResourceBundleMessageSource();
        source.setBasename("classpath:i18n/messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }
}
