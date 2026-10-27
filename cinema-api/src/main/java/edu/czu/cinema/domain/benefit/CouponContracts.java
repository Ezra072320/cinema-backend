package edu.czu.cinema.domain.benefit;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public final class CouponContracts {
  private CouponContracts() {}

  public record TemplateInput(
      @NotBlank @Size(max = 80) String name,
      @NotNull @DecimalMin("0.01") @DecimalMax("9999.99") @Digits(integer = 4, fraction = 2)
          BigDecimal discount,
      @NotNull @DecimalMin("0.01") @DecimalMax("99999.99") @Digits(integer = 5, fraction = 2)
          BigDecimal minimum,
      @Min(1) @Max(10000) int stock,
      @Min(1) @Max(365) int validDays) {}

  public record Template(
      Long id,
      String name,
      BigDecimal discount,
      BigDecimal minimum,
      int remaining,
      int validDays) {}

  public record Coupon(
      Long id,
      String name,
      BigDecimal discount,
      BigDecimal minimum,
      String status,
      String expiresAt,
      Long orderId) {}

  public record Reserve(
      @NotNull @Positive Long userId,
      @NotNull @Positive Long couponId,
      @NotNull @Positive Long orderId,
      @NotNull @DecimalMin("0.01") @DecimalMax("99999999.99") @Digits(integer = 8, fraction = 2)
          BigDecimal originalAmount) {}

  public record Discount(Long couponId, BigDecimal discount, BigDecimal payable) {}
}
