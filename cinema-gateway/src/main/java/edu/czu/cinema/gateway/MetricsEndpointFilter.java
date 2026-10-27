package edu.czu.cinema.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

@Component @Order(-1000)
public final class MetricsEndpointFilter implements WebFilter {
    private static final PathPattern METRICS=PathPatternParser.defaultInstance.parse("/actuator/prometheus/**");
    private final boolean enabled;
    private final byte[] expected;
    public MetricsEndpointFilter(@Value("${cinema.metrics.enabled:false}") boolean enabled,
            @Value("${cinema.metrics.key:}") String key) {
        if (enabled && (key==null || key.length()<32)) throw new IllegalArgumentException("Metrics key must contain at least 32 characters");
        this.enabled=enabled;
        this.expected=key==null ? new byte[0] : key.getBytes(StandardCharsets.UTF_8);
    }
    @Override public Mono<Void> filter(ServerWebExchange exchange,WebFilterChain chain) {
        if (!METRICS.matches(exchange.getRequest().getPath().pathWithinApplication())) return chain.filter(exchange);
        exchange.getResponse().getHeaders().set("Cache-Control","no-store");
        String key=exchange.getRequest().getHeaders().getFirst("X-Metrics-Key");
        if (!enabled) exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
        else if (key==null || !MessageDigest.isEqual(expected,key.getBytes(StandardCharsets.UTF_8)))
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        else return chain.filter(exchange);
        return exchange.getResponse().setComplete();
    }
}
