package edu.czu.cinema.security;

import java.util.Set;
import java.util.function.Supplier;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/** Explicit management API policy. Unknown management routes fail closed. */
public final class AdminRequestAuthorization
    implements AuthorizationManager<RequestAuthorizationContext> {
  private static final Set<String> SELF =
      Set.of("/api/admin/info", "/api/admin/update", "/api/admin/reset", "/api/admin/access");

  public static String permission(String method, String path) {
    if (SELF.contains(path)) return "";
    if (path.equals("/api/statistic/getAll")) return "dashboard:read";
    if (path.equals("/api/statistic/revenue")) return "report:read";
    if (path.equals("/api/statistic/revenue/export")) return "report:export";
    if (path.equals("/api/admin/list")
        || path.equals("/api/admin/addAdmin")
        || path.equals("/api/admin/modStatus")
        || path.startsWith("/api/admin/delete/")
        || path.startsWith("/api/admin/rbac/")) return "admin:manage";
    if (path.startsWith("/api/admin/user/")) return "user:manage";
    if (path.startsWith("/api/admin/governance/")) return "governance:manage";
    String operation = method.equals("GET") || method.equals("HEAD") ? ":read" : ":write";
    if (path.startsWith("/api/admin/movie/")) return "catalog" + operation;
    if (path.startsWith("/api/admin/search/")) return "catalog" + operation;
    if (path.startsWith("/api/admin/hall/")
        || path.equals("/api/admin/seat-template")
        || path.startsWith("/api/admin/seat-template/")) return "venue" + operation;
    if (path.startsWith("/api/admin/schedule/")) return "schedule" + operation;
    if (path.startsWith("/api/admin/benefit/")) return "benefit" + operation;
    if (path.startsWith("/api/admin/order/") && operation.equals(":read")) return "order:read";
    return null;
  }

  @Override
  public AuthorizationDecision check(
      Supplier<Authentication> supplier, RequestAuthorizationContext context) {
    Authentication auth = supplier.get();
    if (auth == null || !auth.isAuthenticated()) return new AuthorizationDecision(false);
    Set<String> granted =
        auth.getAuthorities().stream()
            .map(a -> a.getAuthority())
            .collect(java.util.stream.Collectors.toSet());
    String required =
        permission(context.getRequest().getMethod(), context.getRequest().getServletPath());
    return new AuthorizationDecision(
        granted.contains("ROLE_ADMIN")
            && required != null
            && (required.isEmpty() || granted.contains(required)));
  }
}
