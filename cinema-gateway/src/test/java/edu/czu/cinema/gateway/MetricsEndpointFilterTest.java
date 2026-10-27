package edu.czu.cinema.gateway;

import static org.assertj.core.api.Assertions.*;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class MetricsEndpointFilterTest {
    static final String KEY="test-only-metrics-key-with-at-least-thirty-two-characters";
    @Test void machineKeyProtectsCanonicalEncodedAndTrailingSlashPaths() {
        for (String path:new String[]{"/actuator/prometheus","/actuator/%70rometheus","/actuator/prometheus/"}) {
            var exchange=MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.GET,URI.create("http://localhost"+path)).build());
            new MetricsEndpointFilter(true,KEY).filter(exchange,e -> { fail("Keyless endpoint"); return Mono.empty(); }).block();
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        var exchange=MockServerWebExchange.from(MockServerHttpRequest.get("/actuator/prometheus").header("X-Metrics-Key",KEY));
        var called=new AtomicBoolean();
        new MetricsEndpointFilter(true,KEY).filter(exchange,e -> { called.set(true); return Mono.empty(); }).block();
        assertThat(called.get()).isTrue();
    }
    @Test void defaultOffAndOrdinaryRoutesRemainIndependent() {
        var exchange=MockServerWebExchange.from(MockServerHttpRequest.get("/actuator/prometheus").header("X-Metrics-Key",KEY));
        new MetricsEndpointFilter(false,KEY).filter(exchange,e -> Mono.error(new AssertionError("Disabled endpoint"))).block();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        var called=new AtomicBoolean();
        new MetricsEndpointFilter(false,"").filter(MockServerWebExchange.from(MockServerHttpRequest.get("/api/movie/topSales")),e -> { called.set(true); return Mono.empty(); }).block();
        assertThat(called.get()).isTrue();
    }
    @Test void enabledEndpointRequiresARealKey() {
        assertThatThrownBy(() -> new MetricsEndpointFilter(true,"short")).isInstanceOf(IllegalArgumentException.class);
    }
}
