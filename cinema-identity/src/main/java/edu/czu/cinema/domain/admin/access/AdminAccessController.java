package edu.czu.cinema.domain.admin.access;

import edu.czu.cinema.common.api.R;
import edu.czu.cinema.common.util.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class AdminAccessController {
  private final AdminRbacService rbac;

  public record RoleChange(@NotEmpty @Size(max = 4) Set<String> roles) {}

  public AdminAccessController(AdminRbacService rbac) {
    this.rbac = rbac;
  }

  @GetMapping("/access")
  public R<AdminRbacService.Access> mine() {
    return R.ok(rbac.access(SecurityUtils.requireAdminId()));
  }

  @GetMapping("/rbac/roles")
  public R<List<AdminRbacService.Role>> roles() {
    return R.ok(rbac.definitions());
  }

  @GetMapping("/rbac/assignments")
  public R<Map<Long, Set<String>>> assignments(@RequestParam List<Long> ids) {
    return R.ok(rbac.assignments(ids));
  }

  @PutMapping("/rbac/admins/{id}")
  public R<AdminRbacService.Access> assign(
      @PathVariable long id, @Valid @RequestBody RoleChange change) {
    return R.ok(rbac.assign(id, change.roles()));
  }
}
