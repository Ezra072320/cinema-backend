package edu.czu.cinema.domain.schedule.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 批量排片的请求体.
 *
 * <p>同一场批量共用一个影片/影厅/倍率; 每行时段可单独改价(为空则用批量基础价)。
 * 冲突或不合法的行被跳过并报告原因, 其余行正常入库 —— 见
 * {@link edu.czu.cinema.domain.schedule.vo.ScheduleBatchResultVO}。
 */
public record ScheduleBatchAddDTO(
        @Schema(description = "电影 ID") @NotNull Long movieId,
        @Schema(description = "影厅 ID") @NotNull Long hallId,
        @Schema(description = "时段列表, 最多 100 行") @NotEmpty @Size(max = 100) @Valid List<Session> sessions,
        @Schema(description = "基础票价, 行内未单独改价时用，0.01~9999.99")
        @DecimalMin(value = "0.01") @DecimalMax(value = "9999.99") BigDecimal price,
        @Schema(description = "VIP 座倍率, 不传默认 3.00")
        @DecimalMin(value = "1.0") @DecimalMax(value = "9.99") BigDecimal vipPriceMultiplier,
        @Schema(description = "情侣座倍率, 不传默认 2.50")
        @DecimalMin(value = "1.0") @DecimalMax(value = "9.99") BigDecimal couplePriceMultiplier
) {
    /** 一个批量时段. price 为空则跟随批量基础价. */
    public record Session(
            @Schema(description = "开场时间") @NotNull LocalDateTime startTime,
            @Schema(description = "该行票价, 为空用批量基础价，0.01~9999.99")
            @DecimalMin(value = "0.01") @DecimalMax(value = "9999.99") BigDecimal price
    ) {}
}
