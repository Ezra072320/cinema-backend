package edu.czu.cinema.security;

import static org.assertj.core.api.Assertions.*;

import edu.czu.cinema.config.OpenApiConfig;
import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentationSecurityTest {
  @Test
  void inheritedJwtSecurityIsExplicitForTheUiWithoutOverridingPublicOperations() {
    var config = new OpenApiConfig();
    var doc = config.cinemaOpenApi("test-domain");
    var secured = new Operation();
    var publicOperation = new Operation().security(List.of());
    var login = new Operation();
    doc.paths(
        new Paths()
            .addPathItem("/api/admin/info", new PathItem().get(secured))
            .addPathItem("/api/captcha/image", new PathItem().get(publicOperation))
            .addPathItem("/api/admin/login", new PathItem().post(login)));
    config.inheritedSecurityForUi().customise(doc);
    assertThat(secured.getSecurity())
        .containsExactly(new SecurityRequirement().addList("Authorization"));
    assertThat(publicOperation.getSecurity()).isEmpty();
    assertThat(login.getSecurity()).isEmpty();
    assertThat(doc.getServers()).extracting(s -> s.getUrl()).containsExactly("/");
  }

  @Test
  void explicitAlternativeAuthenticationIsNotReplacedWithTheGlobalDefault() {
    var config = new OpenApiConfig();
    var operation = new Operation().security(List.of(new SecurityRequirement().addList("other")));
    var doc =
        config
            .cinemaOpenApi("test-domain")
            .paths(new Paths().addPathItem("/api/example", new PathItem().get(operation)));
    config.inheritedSecurityForUi().customise(doc);
    assertThat(operation.getSecurity()).containsExactly(new SecurityRequirement().addList("other"));
    config.inheritedSecurityForUi().customise(new OpenAPI());
  }
}
