package edu.czu.cinema.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3 文档配置（搭配 Knife4j 自动增强 UI）.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public org.springdoc.core.customizers.OpenApiCustomizer inheritedSecurityForUi() {
        // Knife4j 4.5 reads operation security directly. Make OpenAPI's inherited
        // default explicit, while retaining any operation's own override.
        return api -> {
            if (api.getSecurity() == null || api.getPaths() == null) return;
            api.getPaths().forEach((path, item) -> item.readOperations().forEach(operation -> {
                if (operation.getSecurity() == null) {
                    operation.setSecurity(JwtOptionalPaths.publicApi(path) ? java.util.List.of()
                            : new java.util.ArrayList<>(api.getSecurity()));
                }
            }));
        };
    }

    @Bean
    public OpenAPI cinemaOpenApi(@org.springframework.beans.factory.annotation.Value("${spring.application.name:cinema}") String application) {
        return new OpenAPI()
                .info(new Info()
                        .title(application + " API")
                        .description("影院管理系统翻新后端接口文档")
                        .version("0.1.0")
                        .contact(new Contact().name("cinema-server"))
                        .license(new License().name("MIT")))
                .servers(java.util.List.of(new io.swagger.v3.oas.models.servers.Server().url("/")))
                .components(new Components().addSecuritySchemes("Authorization",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .in(SecurityScheme.In.HEADER)
                                .name("Authorization")))
                .addSecurityItem(new SecurityRequirement().addList("Authorization"));
    }
}
