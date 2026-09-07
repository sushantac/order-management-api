package com.company.orderapi.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * PR #26 - binding for the {@code app.security.*} configuration block.
 */
@Component
@ConfigurationProperties(prefix = "app.security")
public class SecurityProperties {

    /** Master switch - non-security tests set it to false. */
    private boolean enabled = true;

    /** HS256 signing secret (learning setup; >= 32 chars). */
    private String jwtSecret = "local-learning-secret-change-me-please-32chars";

    /** Static API key accepted by the ApiKeyAuthenticationFilter. */
    private String apiKey = "dev-api-key-orderapi";

    private Cors cors = new Cors();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public Cors getCors() {
        return cors;
    }

    public void setCors(Cors cors) {
        this.cors = cors;
    }

    public static class Cors {
        private List<String> allowedOrigins = new ArrayList<>();

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }
    }
}
