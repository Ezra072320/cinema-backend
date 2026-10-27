package edu.czu.cinema.governance;

import edu.czu.cinema.common.api.R;
import edu.czu.cinema.common.exception.BusinessException;
import edu.czu.cinema.common.util.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/governance")
public class GovernanceController {
  private final ObjectProvider<PersistentSeatRules> rules;

  public GovernanceController(ObjectProvider<PersistentSeatRules> rules) {
    this.rules = rules;
  }

  public record Update(@Min(1) @Max(1000) int qps) {}

  @GetMapping("/seat-rule")
  public R<PersistentSeatRules.View> state() {
    SecurityUtils.requireAdminId();
    var store = rules.getIfAvailable();
    return R.ok(
        store == null ? new PersistentSeatRules.View(false, null, null, false) : store.state());
  }

  @PutMapping("/seat-rule")
  public R<PersistentSeatRules.View> update(@Valid @RequestBody Update q) {
    SecurityUtils.requireAdminId();
    var store = rules.getIfAvailable();
    if (store == null) throw new BusinessException("请先启用本地治理组");
    return R.ok(store.publish(q.qps()));
  }
}
