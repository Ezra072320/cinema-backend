package edu.czu.cinema.domain.schedule.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * 批量改期的请求体.
 *
 * <p>语义是「同时段整体改时刻」: 同一批次里所有**开场时刻与 sourceStartTime 相同**的场次,
 * 改到各自日期上的 targetStartTime 时刻。批次里不同时刻的场次不动。
 *
 * <p>source/target 只取它们的时刻({@code toLocalTime}), 日期部分无意义
 * (每场保留自己的日期)。
 */
public record ScheduleBatchMoveDTO(
        @Schema(description = "批量排片批次号") @NotNull String batchId,
        @Schema(description = "原开场时刻(仅时刻部分参与匹配)") @NotNull LocalDateTime sourceStartTime,
        @Schema(description = "目标开场时刻(仅时刻部分生效)") @NotNull LocalDateTime targetStartTime
) {}
