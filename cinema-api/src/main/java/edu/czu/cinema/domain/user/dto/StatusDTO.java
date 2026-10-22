package edu.czu.cinema.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Schema(description = "用户状态切换请求体")
public record StatusDTO(
        @Schema(description = "用户 ID") @NotNull Long id,
        @Schema(description = "新状态：1 启用 / 0 冻结") @NotNull @Min(0) @Max(1) Integer status
) {}
