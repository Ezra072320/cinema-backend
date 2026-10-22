package edu.czu.cinema.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.*;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Cluster-wide token buckets use Redis TIME, so clock skew between gateways cannot mint extra
 * tokens.
 */
@Component
public class BurstAdmissionFilter implements GlobalFilter, Ordered {
    private static final DefaultRedisScript<Long> SCRIPT =
            new DefaultRedisScript<>(
                    """
                    local t=redis.call('TIME')
                    local now=tonumber(t[1])*1000+tonumber(t[2])/1000
                    local rate=tonumber(ARGV[1]);local capacity=tonumber(ARGV[2])
                    local row=redis.call('HMGET',KEYS[1],'tokens','time')
                    local tokens=tonumber(row[1]) or capacity
                    local last=tonumber(row[2]) or now
                    tokens=math.min(capacity,tokens+math.max(0,now-last)*rate/1000)
                    local allowed=0
                    if tokens>=1 then tokens=tokens-1;allowed=1 end
                    redis.call('HSET',KEYS[1],'tokens',tokens,'time',now)
                    redis.call('PEXPIRE',KEYS[1],math.ceil(capacity/rate*1000)+1000)
                    return allowed
                    """,
                    Long.class);
    private final ReactiveStringRedisTemplate redis;
    private final Map<String, int[]> quotas;
    private final java.util.concurrent.Semaphore decisions =
            new java.util.concurrent.Semaphore(1024);
    private final java.util.concurrent.Semaphore uploads = new java.util.concurrent.Semaphore(8);

    public BurstAdmissionFilter(
            ReactiveStringRedisTemplate redis,
            @Value("${cinema.admission.catalog-rate:1000}") int catalog,
            @Value("${cinema.admission.trade-rate:300}") int trade,
            @Value("${cinema.admission.booking-rate:80}") int booking,
            @Value("${cinema.admission.identity-rate:100}") int identity,
            @Value("${cinema.admission.ai-rate:10}") int ai,
            @Value("${cinema.admission.upload-rate:5}") int upload) {
        this.redis = redis;
        quotas =
                Map.of(
                        "catalog",
                        quota(catalog),
                        "trade",
                        quota(trade),
                        "booking",
                        quota(booking),
                        "identity",
                        quota(identity),
                        "ai",
                        quota(ai),
                        "upload",
                        quota(upload));
    }

    private static int[] quota(int rate) {
        if (rate < 1) throw new IllegalArgumentException("Admission rate must be positive");
        return new int[] {rate, rate};
    }

    @Override
    public int getOrder() {
        return -100;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route == null) return chain.filter(exchange);
        if ("upload".equals(route.getId())) {
            // Slow OSS connections must not occupy all identity workers or share login's breaker.
            return Mono.defer(() -> {
                if (!uploads.tryAcquire()) return reject(exchange, 429);
                return admit(route, exchange, chain).doFinally(signal -> uploads.release());
            });
        }
        return admit(route, exchange, chain);
    }

    private Mono<Void> admit(Route route, ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!decisions.tryAcquire()) return reject(exchange, 429);
        var clean =
                exchange.mutate()
                        .request(
                                r ->
                                        r.headers(
                                                h -> {
                                                    h.remove("X-Service-Key");
                                                    h.remove("X-Operation-Id");
                                                    h.remove("X-User-Id");
                                                    h.remove("X-User-Role");
                                                }))
                        .build();
        int[] q = quotas.getOrDefault(route.getId(), quotas.get("trade"));
        return redis.execute(
                        SCRIPT,
                        List.of("cinema:admission:{" + route.getId() + "}"),
                        List.of(Integer.toString(q[0]), Integer.toString(q[1])))
                .next()
                .defaultIfEmpty(0L)
                .timeout(Duration.ofMillis(300))
                .onErrorReturn(-1L)
                .doFinally(signal -> decisions.release())
                .flatMap(
                        allowed ->
                                allowed == 1
                                        ? chain.filter(clean)
                                        : reject(clean, allowed < 0 ? 503 : 429));
    }

    private Mono<Void> reject(ServerWebExchange e, int status) {
        var response = e.getResponse();
        response.setStatusCode(HttpStatus.valueOf(status));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set("Retry-After", "2");
        String body =
                status == 429
                        ? """
{"code":3001,"msg":"访问人数较多，请稍后重试"}
"""
                        : """
{"code":3001,"msg":"服务暂不可用，请稍后重试"}
""";
        return response.writeWith(
                Mono.just(response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }
}
