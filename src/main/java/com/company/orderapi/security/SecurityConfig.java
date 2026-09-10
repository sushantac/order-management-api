package com.company.orderapi.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.spec.SecretKeySpec;
import java.util.List;

/**
 * PR #26 - security configuration: stateless OAuth2 resource server.
 *
 * <p>JWT bearer tokens are decoded with a symmetric HS256 secret for the
 * learning setup (a real deployment validates signatures against the issuer's
 * published asymmetric JWK set and adds {@code iss}/{@code aud} checks).
 *
 * <p>Security headers (HSTS, CSP, X-Content-Type-Options) are applied in the
 * filter chain; CORS is configured for the documented front-end origin; and
 * {@code @PreAuthorize} protects methods with scope-based authorities.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final SecurityProperties properties;

    public SecurityConfig(SecurityProperties properties) {
        this.properties = properties;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(cors -> cors.configurationSource(corsConfigurationSource()));

        if (properties.isEnabled()) {
            http.oauth2ResourceServer(rs -> rs
                    .jwt(jwt -> jwt.decoder(jwtDecoder())
                            .jwtAuthenticationConverter(new JwtAuthenticationConverter())))
                    .addFilterBefore(new ApiKeyAuthenticationFilter(properties.getApiKey()),
                            UsernamePasswordAuthenticationFilter.class)
                    .authorizeHttpRequests(auth -> auth
                            // Health + API docs stay public.
                            .requestMatchers("/actuator/health", "/actuator/info")
                            .permitAll()
                            .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                            .permitAll()
                            // PR #47: the embedded OAuth 2.1 authorization
                            // server endpoints authenticate themselves.
                            .requestMatchers("/oauth2/**", "/.well-known/**")
                            .permitAll()
                            .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                            // PR #47: /mcp is OAuth-only and scope-gated. A
                            // token (even a valid JWT/API-key) without the mcp
                            // scope is rejected here. Scope is decided by the
                            // authorization server, not by the caller.
                            .requestMatchers("/mcp").hasAuthority("SCOPE_mcp")
                            .anyRequest().authenticated())
                    .headers(headers -> headers
                            .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true))
                            .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'"))
                            .contentTypeOptions(withDefaults -> {
                            }));
        } else {
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        }
        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        SecretKeySpec key = new SecretKeySpec(
                properties.getJwtSecret().getBytes(), "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.getCors().getAllowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "If-Match", "Idempotency-Key", "X-API-Key"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
