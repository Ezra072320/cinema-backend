package edu.czu.cinema.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.PRESERVE_HOST_HEADER_ATTRIBUTE;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.DedupeResponseHeaderGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.PreserveHostHeaderGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.RequestSizeGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.SpringCloudCircuitBreakerFilterFactory;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.DispatcherHandler;

import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

class GatewayRoutesFilterTest {
    private GenericApplicationContext context() {
        var context = new GenericApplicationContext();
        context.registerBean(PathRoutePredicateFactory.class);
        context.registerBean(DedupeResponseHeaderGatewayFilterFactory.class);
        context.registerBean(PreserveHostHeaderGatewayFilterFactory.class);
        context.registerBean(RequestSizeGatewayFilterFactory.class);
        context.registerBean(
                SpringCloudCircuitBreakerFilterFactory.class,
                () ->
                        new SpringCloudCircuitBreakerFilterFactory(
                                mock(ReactiveCircuitBreakerFactory.class),
                                context.getBeanProvider(DispatcherHandler.class)) {
                            @Override
                            public GatewayFilter apply(Config config) {
                                // Isolate route-wide HTTP filters from circuit breaker
                                // implementation details.
                                return (exchange, chain) -> chain.filter(exchange);
                            }

                            @Override
                            protected Mono<Void> handleErrorWithoutFallback(
                                    Throwable error, boolean resumeWithoutError) {
                                return Mono.error(error);
                            }
                        });
        context.refresh();
        return context;
    }

    private java.util.List<Route> routes(GenericApplicationContext context) {
        return new GatewayRoutes()
                .cinemaRoutes(
                        new RouteLocatorBuilder(context),
                        "http://identity:8080",
                        "http://catalog:8080",
                        "http://trade:8080",
                        "http://ai:8080")
                .getRoutes()
                .collectList()
                .block();
    }

    private GatewayFilterChain chain(Route route, GatewayFilterChain terminal) {
        var filters = route.getFilters();
        var chain = terminal;
        for (int i = filters.size() - 1; i >= 0; i--) {
            var filter = filters.get(i);
            var next = chain;
            chain = exchange -> filter.filter(exchange, next);
        }
        return chain;
    }

    @Test void fileUploadsHaveTheirOwnTimeoutInsteadOfTheFiveSecondLoginBudget() {
        try (var context = context()) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/file/upload"));
            var first = routes(context).stream().filter(r -> Boolean.TRUE.equals(Mono.from(r.getPredicate().apply(exchange)).block())).findFirst().orElseThrow();
            assertThat(first.getId()).isEqualTo("upload");
            assertThat(first.getMetadata().get("response-timeout")).isEqualTo(30000);
        }
    }

    @Test
    void everyJavaRoutePreservesHostAndDeduplicatesGatewayAndServiceCorsHeaders() {
        try (var context = context()) {
            var routes = routes(context);
            assertThat(routes).hasSize(6);
            for (var route : routes) {
                var exchange =
                        MockServerWebExchange.from(
                                MockServerHttpRequest.get("http://localhost:8088/api/admin/info"));
                var headers = exchange.getResponse().getHeaders();
                headers.add(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173");
                headers.add(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
                chain(
                                route,
                                next -> {
                                    next.getResponse()
                                            .getHeaders()
                                            .add(
                                                    HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                                                    "http://localhost:5173");
                                    next.getResponse()
                                            .getHeaders()
                                            .add(
                                                    HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS,
                                                    "true");
                                    return Mono.empty();
                                })
                        .filter(exchange)
                        .block();
                assertThat(exchange.<Boolean>getAttribute(PRESERVE_HOST_HEADER_ATTRIBUTE))
                        .as(route.getId())
                        .isTrue();
                assertThat(headers.get(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                        .as(route.getId())
                        .containsExactly("http://localhost:5173");
                assertThat(headers.get(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS))
                        .as(route.getId())
                        .containsExactly("true");
            }
        }
    }

    @Test
    void everyJavaRouteRejectsOversizedRequestsBeforeInvokingTheUpstream() {
        try (var context = context()) {
            for (var route : routes(context)) {
                var exchange =
                        MockServerWebExchange.from(
                                MockServerHttpRequest.post("http://localhost:8088/api/admin/login")
                                        .header(
                                                HttpHeaders.CONTENT_LENGTH,
                                                Long.toString(13L * 1024 * 1024)));
                var reached = new AtomicBoolean();
                chain(
                                route,
                                next -> {
                                    reached.set(true);
                                    return Mono.empty();
                                })
                        .filter(exchange)
                        .block();
                assertThat(exchange.getResponse().getStatusCode())
                        .as(route.getId())
                        .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
                assertThat(reached).as(route.getId()).isFalse();
            }
        }
    }
}
