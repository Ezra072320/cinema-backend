package edu.czu.cinema.transactions;

import edu.czu.cinema.domain.order.dto.OrderAddDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/transaction-lab")
@ConditionalOnProperty(name = "cinema.transactions.lab.enabled", havingValue = "true")
public class TransactionLabController {
  private final CouponTransactionLab lab;

  public TransactionLabController(CouponTransactionLab lab) {
    this.lab = lab;
  }

  public record Request(
      @NotNull @Positive Long userId,
      @NotNull @Positive Long scheduleId,
      @NotEmpty @Size(max = 6) List<Long> ticketIds,
      @NotNull @Positive Long couponId,
      @NotBlank @Size(max = 64) String operationId,
      @Min(0) @Max(30000) int delayMs,
      boolean fail) {}

  @PostMapping("/checkouts")
  public Long run(@Valid @RequestBody Request q) throws Exception {
    return lab.run(
        q.userId(),
        new OrderAddDTO(q.scheduleId(), q.ticketIds(), q.couponId()),
        q.operationId(),
        q.delayMs(),
        q.fail());
  }
}
