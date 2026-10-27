package edu.czu.cinema.benefit;

import edu.czu.cinema.common.api.R;
import edu.czu.cinema.common.util.SecurityUtils;
import edu.czu.cinema.domain.benefit.CouponContracts.*;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
public class CouponController {
  private final CouponService coupons;

  public CouponController(CouponService coupons) {
    this.coupons = coupons;
  }

  @GetMapping("/api/benefit/templates")
  public R<List<Template>> templates() {
    SecurityUtils.requireUserId();
    return R.ok(coupons.templates());
  }

  @GetMapping("/api/admin/benefit/templates")
  public R<List<Template>> adminTemplates() {
    SecurityUtils.requireAdminId();
    return R.ok(coupons.templates());
  }

  @PostMapping("/api/admin/benefit/templates")
  public R<Long> create(@Valid @RequestBody TemplateInput t) {
    SecurityUtils.requireAdminId();
    return R.ok(coupons.create(t));
  }

  @GetMapping("/api/benefit/coupons")
  public R<List<Coupon>> mine() {
    return R.ok(coupons.coupons(SecurityUtils.requireUserId()));
  }

  @PostMapping("/api/benefit/templates/{id}/claim")
  public R<Long> claim(@PathVariable Long id) {
    return R.ok(coupons.claim(SecurityUtils.requireUserId(), id));
  }

  @PostMapping("/internal/benefit/reservations")
  public Discount reserve(@Valid @RequestBody Reserve q) {
    return coupons.reserve(q);
  }

  @PostMapping("/internal/benefit/quote")
  public Discount quote(@Valid @RequestBody Reserve q) {
    return coupons.quote(q);
  }

  @GetMapping("/internal/benefit/coupons")
  public List<Coupon> coupons(@RequestParam Long userId) {
    return coupons.coupons(userId);
  }
}
