package edu.czu.cinema.gateway;

import edu.czu.cinema.observe.RequestLog;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.skywalking.apm.toolkit.webflux.v6.WebFluxSkyWalkingOperators;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

/** Log one routed request after completion, keeping the agent context at capture time. */
@Component
public final class RequestLoggingFilter implements GlobalFilter, Ordered {
    @Override public int getOrder() { return -200; }

    @Override public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return Mono.defer(() -> {
            String supplied = exchange.getRequest().getHeaders().getFirst("X-Trace-Id");
            String requestId = supplied == null || supplied.isBlank() || supplied.length() > 64
                    ? UUID.randomUUID().toString().substring(0, 8) : supplied;
            ServerWebExchange request = exchange.mutate().request(builder -> builder.headers(headers -> headers.set("X-Trace-Id", requestId))).build();
            request.getResponse().getHeaders().set("X-Trace-Id", requestId);
            AtomicReference<RequestLog> observation = new AtomicReference<>();
            WebFluxSkyWalkingOperators.continueTracing(request,
                    () -> observation.set(RequestLog.start(requestId, request.getRequest().getMethod().name())));
            AtomicReference<Throwable> failure = new AtomicReference<>();
            return chain.filter(request).doOnError(failure::set).doFinally(signal -> {
                Route route = request.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
                int status = request.getResponse().getStatusCode() == null
                        ? failure.get() == null ? 200 : 500 : request.getResponse().getStatusCode().value();
                if (signal == SignalType.CANCEL && status < 400) status = 499;
                observation.get().complete(route == null ? "unmapped" : route.getId(), status,
                        signal == SignalType.CANCEL ? "cancelled" : failure.get() == null ? "complete" : "error");
            });
        });
    }
}
