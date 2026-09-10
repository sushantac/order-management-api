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

    /** OAuth 2.1 authorization server settings (PR #47). */
    private OAuth oauth = new OAuth();

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

    public OAuth getOauth() {
        return oauth;
    }

    public void setOauth(OAuth oauth) {
        this.oauth = oauth;
    }

    public static class OAuth {
        /** Issuer URL advertised in RFC 8414 metadata + JWT {@code iss} claim. */
        private String issuer = "http://localhost:8080";

        /** Secret of the confidential in-app MCP client ({@code mcp-server}). */
        private String mcpClientSecret = "mcp-server-secret-learning";

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getMcpClientSecret() {
            return mcpClientSecret;
        }

        public void setMcpClientSecret(String mcpClientSecret) {
            this.mcpClientSecret = mcpClientSecret;
        }
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
