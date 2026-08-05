package com.bank.txn.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI bankingOpenApi() {
        final String scheme = "bearer-jwt";
        return new OpenAPI()
                .info(new Info()
                        .title("Distributed Banking Transaction Service")
                        .version("1.0.0")
                        .description("""
                                Idempotent fund transfers with optimistic locking, a double-entry ledger,
                                Redis-backed rate limiting and Kafka event streaming.

                                Send `Idempotency-Key: <uuid>` on POST /api/v1/transfers. Retrying with the
                                same key replays the original response instead of moving money twice."""))
                .components(new Components().addSecuritySchemes(scheme, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(scheme));
    }
}
