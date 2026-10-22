package edu.czu.cinema.domain.schedule.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 新增排片的请求体.
 *
 * <p>VIP / 情侣倍率是**可选**的: 不传就用默认(3.00 / 2.50). 传了就在建票时生效,
 * 让不同场次可以定不同的价.
 */
public record ScheduleAddDTO(
        @Schema(description = "电影 ID") @NotNull Long movieId,
        @Schema(description = "影厅 ID") @NotNull Long hallId,
        @Schema(description = "开场时间") @NotNull LocalDateTime startTime,
        @Schema(description = "基础票价，0.01~9999.99")
        @DecimalMin(value = "0.01") @DecimalMax(value = "9999.99") BigDecimal price,
        @Schema(description = "VIP 座倍率, 不传默认 3.00")
        @DecimalMin(value = "1.0") @DecimalMax(value = "9.99") BigDecimal vipPriceMultiplier,
        @Schema(description = "情侣座倍率, 不传默认 2.50")
        @DecimalMin(value = "1.0") @DecimalMax(value = "9.99") BigDecimal couplePriceMultiplier
) {}
