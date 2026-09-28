package com.mars.harness.controlcenter.config;

import com.mars.harness.kernel.engine.HarnessEngine;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI metadata for the Control Center API ({@code /v3/api-docs}, {@code /swagger-ui.html}). */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    @Bean
    public OpenAPI controlCenterOpenApi() {
        return new OpenAPI()
                .info(new Info().title("MARS Control Center API").version(HarnessEngine.HARNESS_VERSION)
                        .description("""
                                Observation and human-decision interface over the MARS engine. Read endpoints return \
                                snapshots built from the run's persisted artifacts; the event stream replays and streams \
                                execution events; commands call the engine's own analyze / decide / resume. JSON is \
                                snake_case; absent fields have no value. Errors share one shape: \
                                {code, message, run_id, correlation_id, details}. Commands accept an Idempotency-Key \
                                header. See docs/control-center-api.md.""")
                        .license(new License().name("MIT").url("https://opensource.org/licenses/MIT")))
                .components(new Components()
                        .addSecuritySchemes("devSession", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE).name("MARS_SESSION")
                                .description("Development mode only: session cookie from POST /api/v1/session/login; "
                                        + "commands also need the X-XSRF-TOKEN header echoing the XSRF-TOKEN cookie. "
                                        + "Development identities are not authentication."))
                        .addSecuritySchemes("oidcBearer", new SecurityScheme().type(SecurityScheme.Type.HTTP)
                                .scheme("bearer").bearerFormat("JWT")
                                .description("OIDC mode: a JWT from the configured issuer; roles from the configured claim")))
                .addSecurityItem(new SecurityRequirement().addList("devSession"))
                .addSecurityItem(new SecurityRequirement().addList("oidcBearer"));
    }
}
