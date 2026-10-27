package edu.czu.cinema.security;

import static org.assertj.core.api.Assertions.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class MetricsEndpointFilterTest {
    static final String KEY="test-only-metrics-key-with-at-least-thirty-two-characters";
    @Test void enabledEndpointAcceptsOnlyItsIndependentKey() throws Exception {
        var filter=new MetricsEndpointFilter(true,KEY);
        var request=new MockHttpServletRequest("GET","/actuator/prometheus");
        request.addHeader("X-Metrics-Key",KEY);
        var response=new MockHttpServletResponse();
        var called=new AtomicBoolean();
        filter.doFilter(request,response,(req,res) -> called.set(true));
        assertThat(called.get()).isTrue();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }
    @Test void missingKeyDoesNotFallThroughEvenWithAnAuthorizationHeader() throws Exception {
        for (String path:new String[]{"/actuator/prometheus","/actuator/prometheus/","/actuator/%70rometheus"}) {
            var request=new MockHttpServletRequest("GET",path);
            request.addHeader("Authorization","Bearer ordinary-user-token");
            var response=new MockHttpServletResponse();
            new MetricsEndpointFilter(true,KEY).doFilter(request,response,(req,res) -> fail("Keyless metrics must not reach the endpoint"));
            assertThat(response.getStatus()).isEqualTo(401);
        }
    }
    @Test void disabledMetricsStayClosedWithTheCorrectKey() throws Exception {
        var request=new MockHttpServletRequest("GET","/actuator/prometheus");
        request.addHeader("X-Metrics-Key",KEY);
        var response=new MockHttpServletResponse();
        new MetricsEndpointFilter(false,KEY).doFilter(request,response,(req,res) -> fail("Disabled endpoint"));
        assertThat(response.getStatus()).isEqualTo(404);
    }
    @Test void businessAndReadinessRequestsHaveNoMetricsCredentialDependency() throws Exception {
        for (String path:new String[]{"/api/movie/topSales","/actuator/health/readiness"}) {
            var called=new AtomicBoolean();
            new MetricsEndpointFilter(false,"").doFilter(new MockHttpServletRequest("GET",path),new MockHttpServletResponse(),(req,res) -> called.set(true));
            assertThat(called.get()).isTrue();
        }
    }
    @Test void enabledEndpointCannotStartWithAPlaceholderKey() {
        assertThatThrownBy(() -> new MetricsEndpointFilter(true,"short")).isInstanceOf(IllegalArgumentException.class);
    }
}
