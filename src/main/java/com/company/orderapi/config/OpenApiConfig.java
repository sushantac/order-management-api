package com.company.orderapi.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * PR #25 - OpenAPI document metadata: title, version and description served at
 * /v3/api-docs and rendered by Swagger UI (/swagger-ui.html).
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI orderManagementApi() {
        return new OpenAPI().info(new Info()
                .title("Order Management API")
                .description("Production-grade order management - one PR at a time. "
                        + "See the Learning Roadmap in the README for the taught concepts.")
                .version("v1")
                .license(new License().name("Learning project")));
    }
}
