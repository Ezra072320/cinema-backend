package edu.czu.cinema.remote;

import edu.czu.cinema.domain.benefit.CouponContracts.*;
import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name = "cinema-benefit", contextId = "BenefitClient", url = "${CINEMA_BENEFIT_URL:}")
public interface BenefitClient {
  default Discount reserve(Reserve request) {
    // Phase-one writes can include cold AT metadata/undo preparation. Keep this
    // below the 15s global deadline; read-only calls retain the shared 3s budget.
    return reserveCommand(request, new feign.Request.Options(
        java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(8), true));
  }

  @PostMapping("/internal/benefit/reservations")
  Discount reserveCommand(@RequestBody Reserve request, feign.Request.Options options);

  @GetMapping("/internal/benefit/coupons")
  List<Coupon> coupons(@RequestParam("userId") Long userId);

  @PostMapping("/internal/benefit/quote")
  Discount quote(@RequestBody Reserve request);
}
