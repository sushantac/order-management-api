package com.company.orderapi.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * PR #25 - OpenAPI document metadata: title, version and description served at
 * /v3/api-docs and rendered by Swagger UI (/swagger-ui.html).
 *
 * <p>PR #26 - declares the two authentication schemes enforced by the security
 * config: an OAuth2 bearer JWT ({@code SCOPE_order_read}/{@code SCOPE_order_write})
 * and the {@code X-API-Key} header used by machine clients.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI orderManagementApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Order Management API")
                        .description("Production-grade order management - one PR at a time. "
                                + "Authenticate with a bearer JWT (scopes order_read/order_write) "
                                + "or the X-API-Key header. See the README for the Learning Roadmap.")
                        .version("v1")
                        .license(new License().name("Learning project")))
                .components(new Components()
                        .addSecuritySchemes("bearer-jwt", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("OAuth2 access token. Scopes: order_read, order_write."))
                        .addSecuritySchemes("api-key", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-API-Key")
                                .description("Static API key for machine clients.")))
                .addSecurityItem(new SecurityRequirement().addList("bearer-jwt"));
    }
}
