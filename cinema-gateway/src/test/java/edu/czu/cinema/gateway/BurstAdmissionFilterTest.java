package edu.czu.cinema.gateway;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import reactor.core.publisher.Flux;

class BurstAdmissionFilterTest {
    @Test
    void redisFailureStopsTrafficBeforeItReachesTheBusinessService() {
        var redis = mock(ReactiveStringRedisTemplate.class);
        when(redis.execute(any(), anyList(), anyList()))
                .thenReturn(Flux.error(new IllegalStateException("Redis unavailable")));
        var filter = new BurstAdmissionFilter(redis, 10, 10, 10, 10, 10, 5);
        var exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/api/user/createOrder"));
        exchange.getAttributes()
                .put(
                        ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                        Route.async()
                                .id("booking")
                                .uri("http://localhost:8083")
                                .predicate(e -> true)
                                .build());
        GatewayFilterChain next = mock(GatewayFilterChain.class);
        filter.filter(exchange, next).block();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(503);
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("服务暂不可用")
                .doesNotContain("访问人数较多");
        verifyNoInteractions(next);
    }

    @Test
    void exhaustedQuotaReturns429AndNeverCallsTheService() {
        var redis = mock(ReactiveStringRedisTemplate.class);
        when(redis.execute(any(), anyList(), anyList())).thenReturn(Flux.just(0L));
        var filter = new BurstAdmissionFilter(redis, 10, 10, 10, 10, 10, 5);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/movie/list"));
        exchange.getAttributes()
                .put(
                        ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                        Route.async()
                                .id("catalog")
                                .uri("http://localhost:8082")
                                .predicate(e -> true)
                                .build());
        GatewayFilterChain next = mock(GatewayFilterChain.class);
        filter.filter(exchange, next).block();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(429);
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("访问人数较多");
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("2");
        verifyNoInteractions(next);
    }

    @Test void slowUploadsAreBoundedAndCancellationReturnsTheSlotWithoutBlockingLogin() {
        var redis = mock(ReactiveStringRedisTemplate.class);
        when(redis.execute(any(), anyList(), anyList())).thenReturn(Flux.just(1L));
        var filter = new BurstAdmissionFilter(redis, 10, 10, 10, 10, 10, 5);
        var active = new java.util.ArrayList<reactor.core.Disposable>();
        try {
            for (int i=0; i<8; i++) active.add(filter.filter(exchange("upload"), e -> reactor.core.publisher.Mono.never()).subscribe());
            var rejected = exchange("upload");
            filter.filter(rejected, e -> reactor.core.publisher.Mono.empty()).block();
            assertThat(rejected.getResponse().getStatusCode().value()).isEqualTo(429);
            var login = exchange("identity");
            filter.filter(login, e -> reactor.core.publisher.Mono.empty()).block();
            assertThat(login.getResponse().getStatusCode()).isNull();
            active.removeFirst().dispose();
            var retry = exchange("upload");
            filter.filter(retry, e -> reactor.core.publisher.Mono.empty()).block();
            assertThat(retry.getResponse().getStatusCode()).isNull();
        } finally { active.forEach(reactor.core.Disposable::dispose); }
    }

    private MockServerWebExchange exchange(String route) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/file/upload"));
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                Route.async().id(route).uri("http://localhost:8081").predicate(e -> true).build());
        return exchange;
    }
}
