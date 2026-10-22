package edu.czu.cinema.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.cloud.gateway.config.GlobalCorsProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import reactor.core.publisher.Mono;

class GatewayCorsTest {
    private static final String PREFIX = "spring.cloud.gateway.server.webflux.globalcors";

    private MockEnvironment environment(String origins) throws Exception {
        var env = new MockEnvironment();
        for (var propertySource :
                new YamlPropertySourceLoader()
                        .load("gateway", new ClassPathResource("application.yml"))) {
            env.getPropertySources().addLast(propertySource);
        }
        if (origins != null) env.setProperty("CORS_ALLOWED_ORIGINS", origins);
        return env;
    }

    private MockServerWebExchange request(String origins, String origin, boolean preflight)
            throws Exception {
        var properties =
                Binder.get(environment(origins))
                        .bind(PREFIX, Bindable.of(GlobalCorsProperties.class))
                        .orElseGet(GlobalCorsProperties::new);
        var source = new UrlBasedCorsConfigurationSource();
        source.setCorsConfigurations(properties.getCorsConfigurations());
        var builder =
                preflight
                        ? MockServerHttpRequest.options("http://gateway:8080/api/admin/login")
                        : MockServerHttpRequest.post("http://gateway:8080/api/admin/login");
        builder.header("Origin", origin);
        if (preflight) {
            builder.header("Access-Control-Request-Method", "POST");
            builder.header(
                    "Access-Control-Request-Headers", "content-type,authorization,idempotency-key");
        }
        var exchange = MockServerWebExchange.from(builder);
        new CorsWebFilter(source)
                .filter(exchange, next -> next.getResponse().writeWith(Mono.empty()))
                .block();
        return exchange;
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://localhost:8088",
                "http://127.0.0.1:8088",
                "http://localhost:5173",
                "http://127.0.0.1:5173"
            })
    void browserPostAndPreflightPassTheGateway(String origin) throws Exception {
        assertThat(
                        environment(null)
                                .getProperty(
                                        PREFIX + ".add-to-simple-url-handler-mapping",
                                        Boolean.class,
                                        false))
                .isTrue();
        for (boolean preflight : new boolean[] {false, true}) {
            var exchange = request(null, origin, preflight);
            assertThat(exchange.getResponse().getStatusCode()).isNull();
            assertThat(exchange.getResponse().getHeaders().getAccessControlAllowOrigin())
                    .isEqualTo(origin);
        }
    }

    @Test
    void unrelatedWebsiteIsRejected() throws Exception {
        for (boolean preflight : new boolean[] {false, true}) {
            assertThat(
                            request(null, "https://untrusted.example", preflight)
                                    .getResponse()
                                    .getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void productionOriginOverridesTheLocalAllowlist() throws Exception {
        var allowed = request("https://cinema.example.com", "https://cinema.example.com", true);
        assertThat(allowed.getResponse().getHeaders().getAccessControlAllowOrigin())
                .isEqualTo("https://cinema.example.com");
        assertThat(
                        request("https://cinema.example.com", "http://localhost:8088", true)
                                .getResponse()
                                .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
