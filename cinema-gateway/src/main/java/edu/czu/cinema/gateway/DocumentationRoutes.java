package edu.czu.cinema.gateway;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.*;

/** A development-only document surface. Gateway keeps its reactive dependency boundary. */
@Configuration
@ConditionalOnProperty(name = "cinema.docs.enabled", havingValue = "true")
public class DocumentationRoutes {
  private static final Set<String> DOMAINS =
      Set.of("identity", "catalog", "trade", "ai", "notify", "search", "benefit");
  private final List<String> domains;

  public DocumentationRoutes(
      @Value("${cinema.docs.services:identity,catalog,trade,ai}") String configured) {
    var selected = new LinkedHashSet<String>();
    for (String item : configured.split(",")) {
      String domain = item.trim();
      if (!DOMAINS.contains(domain))
        throw new IllegalArgumentException("Unknown documentation domain");
      selected.add(domain);
    }
    if (selected.isEmpty())
      throw new IllegalArgumentException("Select at least one documentation domain");
    domains = List.copyOf(selected);
  }

  @Bean
  public RouteLocator documentationProxy(RouteLocatorBuilder builder) {
    var routes =
        builder
            .routes()
            .route(
                "docs-ui",
                r ->
                    r.path("/doc.html", "/webjars/**")
                        .filters(
                            f ->
                                f.removeRequestHeader("X-Service-Key")
                                    .removeRequestHeader("X-Operation-Id")
                                    .addResponseHeader("Cache-Control", "no-store"))
                        .metadata("response-timeout", 30000)
                        .uri("lb://cinema-identity"));
    for (String domain : domains) {
      routes.route(
          "docs-" + domain,
          r ->
              r.path("/v3/api-docs/" + domain)
                  .filters(
                      f ->
                          f.setPath("/v3/api-docs")
                              .removeRequestHeader("X-Service-Key")
                              .removeRequestHeader("X-Operation-Id")
                              .addResponseHeader("Cache-Control", "no-store"))
                  .metadata("response-timeout", 30000)
                  .uri("lb://cinema-" + domain));
    }
    return routes.build();
  }

  @Bean
  public RouterFunction<ServerResponse> documentationConfiguration() {
    return RouterFunctions.route()
        .GET(
            "/v3/api-docs/swagger-config",
            request ->
                ServerResponse.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Cache-Control", "no-store")
                    .bodyValue(
                        Map.of(
                            "urls",
                            domains.stream()
                                .map(
                                    domain ->
                                        Map.of(
                                            "name",
                                            "cinema-" + domain,
                                            "url",
                                            "/v3/api-docs/" + domain))
                                .toList(),
                            "validatorUrl",
                            "",
                            "urls.primaryName",
                            "cinema-" + domains.getFirst())))
        .build();
  }
}
