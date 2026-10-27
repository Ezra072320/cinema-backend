package edu.czu.cinema.gateway;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.reactive.server.WebTestClient;

class DocumentationRoutesTest {
  @Test
  void documentationIsAbsentByDefault() {
    new ApplicationContextRunner()
        .withUserConfiguration(DocumentationRoutes.class)
        .run(context -> assertThat(context).doesNotHaveBean(DocumentationRoutes.class));
  }

  @Test
  void selectedDomainsUseOnlyFixedServiceRoutesAndStableOrder() {
    var docs = new DocumentationRoutes("catalog,trade,catalog");
    WebTestClient.bindToRouterFunction(docs.documentationConfiguration())
        .build()
        .get()
        .uri("/v3/api-docs/swagger-config")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("Cache-Control", "no-store")
        .expectBody()
        .jsonPath("$.urls.length()")
        .isEqualTo(2)
        .jsonPath("$.urls[0].name")
        .isEqualTo("cinema-catalog")
        .jsonPath("$.urls[1].url")
        .isEqualTo("/v3/api-docs/trade");
    assertThatThrownBy(() -> new DocumentationRoutes("identity,http://remote"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new DocumentationRoutes(""))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
