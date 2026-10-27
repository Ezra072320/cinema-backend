package edu.czu.cinema.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import edu.czu.cinema.common.util.ContextHolder;
import edu.czu.cinema.common.util.JwtUtil;
import edu.czu.cinema.domain.admin.AccountAccess;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

class AdminAuthorizationTest {
  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
    ContextHolder.clear();
  }

  boolean allowed(String method, String path, String... rights) {
    var request = new MockHttpServletRequest(method, path);
    request.setServletPath(path);
    var auth =
        new UsernamePasswordAuthenticationToken(
            "admin", "unused", Arrays.stream(rights).map(SimpleGrantedAuthority::new).toList());
    return new AdminRequestAuthorization()
        .check(() -> auth, new RequestAuthorizationContext(request))
        .isGranted();
  }

  @Test
  void readPrivilegeCannotWriteOrCrossDomains() {
    assertThat(allowed("GET", "/api/admin/search/status", "ROLE_ADMIN", "catalog:read")).isTrue();
    assertThat(allowed("POST", "/api/admin/search/rebuild", "ROLE_ADMIN", "catalog:read")).isFalse();
    assertThat(allowed("POST", "/api/admin/search/rebuild", "ROLE_ADMIN", "catalog:write")).isTrue();
    assertThat(allowed("GET", "/api/admin/movie/sync/status", "ROLE_ADMIN", "catalog:read"))
        .isTrue();
    assertThat(allowed("POST", "/api/admin/movie/sync", "ROLE_ADMIN", "catalog:read")).isFalse();
    assertThat(allowed("GET", "/api/statistic/revenue", "ROLE_ADMIN", "catalog:read")).isFalse();
    assertThat(allowed("GET", "/api/statistic/revenue", "ROLE_ADMIN", "report:read")).isTrue();
    assertThat(allowed("GET", "/api/statistic/revenue/export", "ROLE_ADMIN", "report:read")).isFalse();
    assertThat(allowed("GET", "/api/statistic/revenue/export", "ROLE_ADMIN", "report:export")).isTrue();
    assertThat(allowed("GET", "/api/admin/movie/sync/status", "ROLE_USER", "catalog:read"))
        .isFalse();
  }

  @Test
  void unknownManagementPathsFailClosedAndSelfServiceNeedsAdmin() {
    assertThat(allowed("POST", "/api/admin/new-operation", "ROLE_ADMIN", "admin:manage")).isFalse();
    assertThat(allowed("POST", "/api/admin/seat-template-bypass", "ROLE_ADMIN", "venue:write"))
        .isFalse();
    assertThat(allowed("GET", "/api/admin/access", "ROLE_ADMIN")).isTrue();
    assertThat(allowed("GET", "/api/admin/access", "ROLE_USER")).isFalse();
  }

  @Test
  void sameSignedTokenGetsCurrentPrivilegesOnEachRequest() throws Exception {
    var jwt = new JwtUtil("test-only-secret-with-more-than-thirty-two-bytes", 12);
    var claims =
        new HashMap<>(ContextHolder.toClaims(new ContextHolder.Principal(7L, "operator", "ADMIN")));
    claims.put("pv", "version");
    claims.put("permissions", List.of("admin:manage")); // token privileges must be ignored
    String token = jwt.genToken(claims);
    var accounts = mock(AccountStateVerifier.class);
    when(accounts.verify("ADMIN", 7L, "version"))
        .thenReturn(
            new AccountAccess(true, Set.of("catalog:write")),
            new AccountAccess(true, Set.of("catalog:read")));
    var filter = new JwtAuthenticationFilter(jwt, accounts);
    for (String expected : List.of("catalog:write", "catalog:read")) {
      SecurityContextHolder.clearContext();
      var request = new MockHttpServletRequest("GET", "/api/admin/info");
      request.addHeader("Authorization", token);
      filter.doFilter(
          request,
          new MockHttpServletResponse(),
          (req, resp) -> {
            var actual =
                SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                    .map(a -> a.getAuthority())
                    .toList();
            assertThat(actual).containsExactlyInAnyOrder("ROLE_ADMIN", expected);
          });
      assertThat(ContextHolder.current()).isNull();
    }
    verify(accounts, times(2)).verify("ADMIN", 7L, "version");
  }

  @Test
  void unavailableIdentityReturns503AndPreservesRetryContract() throws Exception {
    var jwt = new JwtUtil("test-only-secret-with-more-than-thirty-two-bytes", 12);
    var accounts = mock(AccountStateVerifier.class);
    when(accounts.verify(any(), any(), any()))
        .thenThrow(new edu.czu.cinema.common.exception.ServiceUnavailableException());
    var request = new MockHttpServletRequest("GET", "/api/admin/info");
    request.addHeader(
        "Authorization",
        jwt.genToken(ContextHolder.toClaims(new ContextHolder.Principal(7L, "admin", "ADMIN"))));
    var response = new MockHttpServletResponse();
    new JwtAuthenticationFilter(jwt, accounts)
        .doFilter(
            request, response, (req, resp) -> fail("Unavailable identity cannot reach a handler"));
    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getHeader("Retry-After")).isEqualTo("2");
  }
}
