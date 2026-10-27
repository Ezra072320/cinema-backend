package edu.czu.cinema.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.factory.SpringCloudCircuitBreakerFilterFactory;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.GatewayFilterSpec;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

import java.util.Set;

@Configuration
public class GatewayRoutes {
    private static final Set<String> FAILURE_CODES = Set.of("500", "502", "503", "504");

    @Bean
    public RouteLocator cinemaRoutes(
            RouteLocatorBuilder b,
            @Value("${CINEMA_IDENTITY_URI:lb://cinema-identity}") String identity,
            @Value("${CINEMA_CATALOG_URI:lb://cinema-catalog}") String catalog,
            @Value("${CINEMA_TRADE_URI:lb://cinema-trade}") String trade,
            @Value("${CINEMA_AI_URI:lb://cinema-ai}") String ai,
            @Value("${CINEMA_NOTIFY_URI:lb://cinema-notify}") String notify,
            @Value("${CINEMA_NOTIFY_WS_URI:lb:ws://cinema-notify}") String notifySocket,
            @Value("${CINEMA_SEARCH_URI:lb://cinema-search}") String search,
            @Value("${CINEMA_BENEFIT_URI:lb://cinema-benefit}") String benefit) {
        return b.routes()
                .route("benefit",r -> r.path("/api/benefit/**","/api/admin/benefit/**").filters(f -> httpFilters(f).circuitBreaker(c -> protect(c,"benefit"))).uri(benefit))
                .route("search", r -> r.path("/api/search/**", "/api/admin/search/**")
                        .filters(f -> httpFilters(f).circuitBreaker(c -> protect(c,"search"))).uri(search))
                .route("notify-socket",r -> r.path("/api/notify/socket")
                        .filters(GatewayRoutes::httpFilters).uri(notifySocket))
                .route("notify",r -> r.path("/api/notify/**")
                        .filters(f -> httpFilters(f).circuitBreaker(c -> protect(c,"notify"))).uri(notify))
                .route(
                        "ai",
                        r ->
                                r.path("/api/ai/**")
                                        .filters(GatewayRoutes::httpFilters)
                                        .metadata("response-timeout", 120000)
                                        .uri(ai))
                .route(
                        "booking",
                        r ->
                                r.path("/api/user/createOrder")
                                        .filters(
                                                f ->
                                                        httpFilters(f)
                                                                .circuitBreaker(
                                                                        c -> protect(c, "booking")))
                                        .metadata("response-timeout", 20000)
                                        .uri(trade))
                .route(
                        "trade",
                        r ->
                                r.path(
                                                "/api/order/**",
                                                "/api/user/orders/**",
                                                "/api/user/refund/**",
                                                "/api/user/cancelOrder/**",
                                                "/api/user/score/**",
                                                "/api/user/scores",
                                                "/api/movie/*/reviews",
                                                "/api/hall/**",
                                                "/api/schedule/**",
                                                "/api/ticket/**",
                                                "/api/admin/governance/**",
                                                "/api/admin/order/**",
                                                "/api/admin/hall/**",
                                                "/api/admin/schedule/**",
                                                "/api/admin/seat-template/**",
                                                "/api/statistic/**")
                                        .filters(
                                                f ->
                                                        httpFilters(f)
                                                                .circuitBreaker(
                                                                        c -> protect(c, "trade")))
                                        .uri(trade))
                .route(
                        "catalog",
                        r ->
                                r.path("/api/movie/**", "/api/admin/movie/**")
                                        .filters(
                                                f ->
                                                        httpFilters(f)
                                                                .circuitBreaker(
                                                                        c -> protect(c, "catalog")))
                                        .uri(catalog))
                .route(
                        "upload",
                        r -> r.path("/api/file/**")
                                .filters(f -> httpFilters(f).circuitBreaker(c -> protect(c, "upload")))
                                .metadata("response-timeout", 30000)
                                .uri(identity))
                .route(
                        "identity",
                        r ->
                                r.path(
                                                "/api/user/**",
                                                "/api/admin/**",
                                                "/api/captcha/**")
                                        .filters(
                                                f ->
                                                        httpFilters(f)
                                                                .circuitBreaker(
                                                                        c ->
                                                                                protect(
                                                                                        c,
                                                                                        "identity")))
                                        .uri(identity))
                .build();
    }

    private static GatewayFilterSpec httpFilters(GatewayFilterSpec filters) {
        // Java RouteLocator routes do not inherit YAML RouteDefinition default-filters.
        return filters.setRequestSize(DataSize.ofMegabytes(12))
                .preserveHostHeader()
                .dedupeResponseHeader(
                        "Access-Control-Allow-Credentials Access-Control-Allow-Origin",
                        "RETAIN_FIRST");
    }

    private static void protect(SpringCloudCircuitBreakerFilterFactory.Config config, String name) {
        config.setName(name)
                .setFallbackUri("forward:/capacity/fallback")
                .setStatusCodes(FAILURE_CODES);
    }
}
