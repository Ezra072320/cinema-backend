package edu.czu.cinema.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.filter.CorsFilter;

class BrowserCorsTest {
    private CorsFilter configuredFilter(String origins) throws Exception {
        var env = new MockEnvironment();
        for (var source :
                new YamlPropertySourceLoader()
                        .load("common", new ClassPathResource("cinema-common.yml"))) {
            env.getPropertySources().addLast(source);
        }
        if (origins != null) env.setProperty("CORS_ALLOWED_ORIGINS", origins);
        var config = new SecurityConfig(mock(JwtAuthenticationFilter.class), new ObjectMapper());
        ReflectionTestUtils.setField(
                config,
                "corsAllowedOriginPatterns",
                env.getProperty("cinema.cors.allowed-origin-patterns").split(","));
        return config.corsFilter();
    }

    private MockHttpServletResponse request(CorsFilter filter, String origin, boolean preflight)
            throws Exception {
        var request =
                new MockHttpServletRequest(preflight ? "OPTIONS" : "POST", "/api/admin/login");
        request.setServerName("cinema-identity");
        request.setServerPort(8080);
        request.addHeader("Origin", origin);
        if (preflight) {
            request.addHeader("Access-Control-Request-Method", "POST");
            request.addHeader(
                    "Access-Control-Request-Headers", "content-type,authorization,idempotency-key");
        }
        var response = new MockHttpServletResponse();
        filter.doFilter(
                request, response, (req, resp) -> ((HttpServletResponse) resp).setStatus(204));
        return response;
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://localhost:8088",
                "http://127.0.0.1:8088",
                "http://localhost:5173",
                "http://127.0.0.1:5173"
            })
    void browserPostAndPreflightReachTheService(String origin) throws Exception {
        var filter = configuredFilter(null);
        for (boolean preflight : new boolean[] {false, true}) {
            var response = request(filter, origin, preflight);
            assertThat(response.getStatus()).isEqualTo(preflight ? 200 : 204);
            assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo(origin);
        }
    }

    @Test
    void unrelatedWebsiteCannotCallTheApi() throws Exception {
        var response = request(configuredFilter(null), "https://untrusted.example", false);
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void configuredProductionOriginReplacesLocalDefaults() throws Exception {
        var filter = configuredFilter("https://cinema.example.com");
        assertThat(request(filter, "https://cinema.example.com", false).getStatus()).isEqualTo(204);
        assertThat(request(filter, "http://localhost:8088", false).getStatus()).isEqualTo(403);
    }
}
