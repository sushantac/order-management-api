package com.company.orderapi.authorization;

import com.company.orderapi.security.SecurityProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2AccessTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2RefreshTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * PR #47 - embedded OAuth 2.1 authorization server. The app is BOTH the
 * authorization server (issues tokens) and the resource server (validates
 * them) for the /mcp endpoint - one symmetric HS256 secret signs and
 * verifies, so there is no external IdP to spin up in the learning setup.
 *
 * <p>OAuth 2.1 rules the config demonstrates:
 * <ul>
 *   <li><b>PKCE S256 required</b> for the public browser client
 *       ({@code mcp-console}, {@code requireProofKey=true},
 *       {@code ClientAuthenticationMethod.NONE} - no client secret).</li>
 *   <li><b>Confidential clients authenticate</b> to the token endpoint with
 *       {@code client_secret_basic} ({@code mcp-server}, {@code mcp-internal}).</li>
 *   <li><b>Rotating refresh tokens</b>: {@code reuseRefreshTokens(false)}, so
 *       every refresh invalidates the previous token (OAuth 2.1 removed the
 *       non-rotating refresh-token option).</li>
 *   <li><b>Scoped access tokens</b>: a {@code scope} claim is embedded in the
 *       JWT; the resource-server chain demands {@code SCOPE_mcp} at /mcp.</li>
 *   <li><b>Short-lived access tokens</b> (15 min) + single-use, rotating
 *       authorization codes (the authorization-code grant).</li>
 * </ul>
 *
 * <p>Access tokens are JWT (HS256) produced by an explicitly wired
 * {@link JwtGenerator}; the {@code scope} claim is emitted as the
 * space-delimited string the existing {@link com.company.orderapi.security.JwtAuthenticationConverter}
 * expects. The {@code /oauth2/jwks} endpoint serves the shared key so any
 * OAuth client can fetch server metadata (RFC 8414
 * {@code /.well-known/oauth-authorization-server}).
 */
@Configuration(proxyBeanMethods = false)
public class OAuth2AuthorizationServerConfig {

    /** Secret of the confidential {@code mcp-internal} client (scope {@code internal}). */
    public static final String MCP_INTERNAL_SECRET = "mcp-internal-secret-learning";

    /** Every /oauth2/* + /.well-known/* request goes to the AS filter chain. */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http)
            throws Exception {
        OAuth2AuthorizationServerConfigurer configurer =
                OAuth2AuthorizationServerConfigurer.authorizationServer();
        http
                .securityMatcher(configurer.getEndpointsMatcher())
                // The token/authorize endpoints do their own client auth; they
                // must not be blocked by the CSRF filter before auth runs.
                .csrf(csrf -> csrf.ignoringRequestMatchers(configurer.getEndpointsMatcher()))
                .with(configurer, (oauth2AuthServer) -> { })
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated());
        return http.build();
    }

    @Bean
    public RegisteredClientRepository registeredClientRepository(SecurityProperties properties) {
        String mcpSecret = properties.getOauth().getMcpClientSecret();

        // Confidential machine-to-machine client: client_credentials grant,
        // basic client authentication, scope "mcp".
        RegisteredClient mcpServer = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("mcp-server")
                .clientSecret("{noop}" + mcpSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("mcp")
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(15))
                        .build())
                .build();

        // Public, browser-based client: PKCE + rotating refresh tokens.
        RegisteredClient mcpConsole = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("mcp-console")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("http://127.0.0.1:8080/callback")
                .scope("mcp")
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)         // PCKE S256 always on
                        .requireAuthorizationConsent(false)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(15))
                        .refreshTokenTimeToLive(Duration.ofDays(1))
                        .reuseRefreshTokens(false)     // OAuth 2.1: rotate
                        .build())
                .build();

        // Confidential client WITHOUT the mcp scope - proves scope enforcement.
        RegisteredClient mcpInternal = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("mcp-internal")
                .clientSecret("{noop}" + MCP_INTERNAL_SECRET)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("internal")
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(15))
                        .build())
                .build();

        return new InMemoryRegisteredClientRepository(mcpServer, mcpConsole, mcpInternal);
    }

    @Bean
    public OAuth2AuthorizationService authorizationService() {
        return new InMemoryOAuth2AuthorizationService();
    }

    /**
     * The HS256 symmetric key source: the SAME secret the resource server uses
     * to validate JWTs, so a token issued here verifies there without any
     * asymmetric-key exchange.
     */
    @Bean
    public JWKSource<SecurityContext> jwkSource(SecurityProperties properties) {
        SecretKeySpec key = new SecretKeySpec(
                properties.getJwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        OctetSequenceKey jwk = new OctetSequenceKey.Builder(key)
                .keyID("orderapi-learning-hs256")
                .algorithm(JWSAlgorithm.HS256)
                .build();
        return new ImmutableJWKSet<>(new JWKSet(jwk));
    }

    @Bean
    public JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        return new NimbusJwtEncoder(jwkSource);
    }

    /** Emits the scope claim as the space-delimited string the converter reads. */
    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> jwtTokenCustomizer() {
        return context -> {
            if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                // JwtGenerator defaults the JWS header to RS256; we only hold an
                // HS256 key, so sign with the symmetric secret instead.
                context.getJwsHeader().algorithm(MacAlgorithm.HS256);
                context.getClaims().claim("scope",
                        String.join(" ", context.getAuthorizedScopes()));
            }
        };
    }

    @Bean
    public OAuth2TokenGenerator<?> oAuth2TokenGenerator(
            JwtEncoder jwtEncoder,
            OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer) {
        JwtGenerator jwtGenerator = new JwtGenerator(jwtEncoder);
        jwtGenerator.setJwtCustomizer(tokenCustomizer);
        return new DelegatingOAuth2TokenGenerator(
                jwtGenerator,
                new OAuth2AccessTokenGenerator(),
                new OAuth2RefreshTokenGenerator());
    }

    @Bean
    public AuthorizationServerSettings authorizationServerSettings(SecurityProperties properties) {
        return AuthorizationServerSettings.builder()
                .issuer(properties.getOauth().getIssuer())
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}