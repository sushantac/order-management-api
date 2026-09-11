package com.company.orderapi.mcp;

import com.company.orderapi.security.SecurityProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #47 integration tests: the OAuth 2.1 authorization server built INTO the
 * app issues JWT bearer tokens, the resource server chain validates them, and
 * {@code /mcp} requires the {@code mcp} scope.
 *
 * <p>Unlike {@link McpServerSdkIntegrationTest} (security disabled), security
 * is ON here: every /mcp call must present an OAuth access token. Tokens are
 * minted against the REAL token endpoint via the confidential
 * {@code client_credentials} flow.
 *
 * <p>PR #48: also verifies MCP tool invocation audit trail is recorded.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = {
        "integration.database.tag=McpOAuth2IntegrationTest",
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false",
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none"
})
class McpOAuth2IntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String ISSUER = "http://localhost:8080";

    @LocalServerPort
    private int port;

    @Autowired
    private SecurityProperties securityProperties;

    @Autowired
    private RegisteredClientRepository clientRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void clientCredentialsTokenGrantsMcpAccess() throws Exception {
        String accessToken = clientCredentialsToken("mcp-server", "mcp");

        assertThat(accessToken).isNotBlank();
        SignedJWT jwt = SignedJWT.parse(accessToken);
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.HS256);
        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        assertThat(claims.getIssuer()).isEqualTo(ISSUER);
        assertThat(claims.getStringClaim("scope")).isEqualTo("mcp");

        try (McpSyncClient client = clientWithBearer(accessToken)) {
            client.initialize();
            List<McpSchema.Tool> tools = client.listTools().tools();
            assertThat(tools)
                    .extracting(McpSchema.Tool::name)
                    .containsExactlyInAnyOrder("api_health", "order_status", "product_search");
        }
    }

    @Test
    void noTokenIsRejectedOnTheMcpEndpoint() throws Exception {
        HttpResponse<String> response = postMcp(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                        + "\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},"
                        + "\"clientInfo\":{\"name\":\"raw\",\"version\":\"1.0\"}}}",
                null);
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void tokenWithoutMcpScopeCannotCallTheMcpEndpoint() throws Exception {
        String token = clientCredentialsToken("mcp-internal", "internal",
                com.company.orderapi.authorization.OAuth2AuthorizationServerConfig.MCP_INTERNAL_SECRET);
        assertThat(SignedJWT.parse(token).getJWTClaimsSet().getStringClaim("scope"))
                .isEqualTo("internal");

        HttpResponse<String> response = postMcp(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                        + "\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},"
                        + "\"clientInfo\":{\"name\":\"raw\",\"version\":\"1.0\"}}}",
                token);
        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test
    void tokenEndpointRejectsWrongClientSecret() {
        HttpResponse<String> response = tokenRequest("mcp-server", "wrong-secret", "mcp");
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void authorizationServerMetadataIsPublished() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port
                                + "/.well-known/oauth-authorization-server"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"issuer\":\"" + ISSUER + "\"")
                .contains("\"token_endpoint\"")
                .contains("\"authorization_endpoint\"");
    }

    @Test
    void publicClientRequiresPkceAndCarriesNoSecret() {
        RegisteredClient console = clientRepository.findByClientId("mcp-console");
        assertThat(console).isNotNull();
        assertThat(console.getClientSecret()).isNull();
        assertThat(console.getClientAuthenticationMethods())
                .doesNotContain(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
        assertThat(console.getClientSettings().isRequireProofKey()).isTrue();
    }

    // --- PR #48: MCP tool audit tests ---------------------------------------

    @Test
    void toolInvocationCreatesAuditTrail() throws Exception {
        String accessToken = clientCredentialsToken("mcp-server", "mcp");

        try (McpSyncClient client = clientWithBearer(accessToken)) {
            client.initialize();
            client.callTool(new McpSchema.CallToolRequest("api_health", Map.of()));
        }

        // Verify audit entry was created
        List<Map<String, Object>> audits = jdbc.queryForList(
                "SELECT session_id, actor, tool_name, arguments_json, success, error_message " +
                "FROM mcp_tool_audit WHERE tool_name = 'api_health' ORDER BY occurred_at DESC LIMIT 1");

        assertThat(audits).hasSize(1);
        Map<String, Object> audit = audits.get(0);
        assertThat(audit.get("actor")).isEqualTo("mcp-server");
        assertThat(audit.get("tool_name")).isEqualTo("api_health");
        assertThat(audit.get("success")).isEqualTo(true);
        assertThat(audit.get("error_message")).isNull();
    }

    @Test
    void failedToolInvocationRecordsError() throws Exception {
        String accessToken = clientCredentialsToken("mcp-server", "mcp");

        try (McpSyncClient client = clientWithBearer(accessToken)) {
            client.initialize();
            // Call a tool with invalid arguments to trigger failure
            client.callTool(new McpSchema.CallToolRequest("order_status", Map.of("orderId", -1L)));
        }

        List<Map<String, Object>> audits = jdbc.queryForList(
                "SELECT session_id, actor, tool_name, arguments_json, success, error_message " +
                "FROM mcp_tool_audit WHERE tool_name = 'order_status' ORDER BY occurred_at DESC LIMIT 1");

        assertThat(audits).hasSize(1);
        Map<String, Object> audit = audits.get(0);
        assertThat(audit.get("actor")).isEqualTo("mcp-server");
        assertThat(audit.get("tool_name")).isEqualTo("order_status");
        assertThat(audit.get("success")).isEqualTo(false);
        assertThat(audit.get("error_message")).isNotNull();
    }

    // --- helpers ------------------------------------------------------------

    private String clientCredentialsToken(String clientId, String scope) {
        return clientCredentialsToken(clientId, scope,
                securityProperties.getOauth().getMcpClientSecret());
    }

    private String clientCredentialsToken(String clientId, String scope, String secret) {
        HttpResponse<String> response = tokenRequest(clientId, secret, scope);

        assertThat(response.statusCode()).isEqualTo(200);
        try {
            var body = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(response.body());
            return body.get("access_token").asText();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot parse token response: " + response.body(), e);
        }
    }

    private HttpResponse<String> tokenRequest(String clientId, String secret, String scope) {
        try {
            return HttpClient.newBuilder().build().send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + port + "/oauth2/token"))
                            .header(HttpHeaders.CONTENT_TYPE,
                                    MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                            .header(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder()
                                    .encodeToString((clientId + ":" + secret)
                                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "grant_type=client_credentials&scope=" + scope))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("Token request failed", e);
        }
    }

    private HttpResponse<String> postMcp(String body, String bearerToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/mcp"))
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .header(HttpHeaders.ACCEPT, "application/json, text/event-stream");
        if (bearerToken != null) {
            builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken);
        }
        return HttpClient.newHttpClient().send(
                builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private McpSyncClient clientWithBearer(String accessToken) {
        return McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                        .endpoint("/mcp")
                        .customizeRequest(request ->
                                request.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                        .build())
                .clientInfo(new McpSchema.Implementation("order-management-api-oauth-test", "1.0.0"))
                .build();
    }
}