package edu.czu.cinema.domain.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(description = "订单支付请求体")
public record OrderPayDTO(
        @Schema(description = "订单 ID") @NotNull Long orderId,
        @Schema(description = "支付渠道：WECHAT / ALIPAY / CARD") @NotBlank String channel
) {}