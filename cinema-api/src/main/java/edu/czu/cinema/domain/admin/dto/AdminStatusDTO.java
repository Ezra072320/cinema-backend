package edu.czu.cinema.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 管理员账号状态切换请求体.
 *
 * <p>替代原先 {@code Map<String,Object>} 手工拆参：类型/取值在绑定阶段即校验，
 * status 白名单 {0,1}，杜绝写入 99 之类脏状态（P1-R5）。</p>
 */
@Schema(description = "管理员状态切换请求体")
public record AdminStatusDTO(
        @Schema(description = "管理员名") @NotBlank String name,
        @Schema(description = "新状态：1 启用 / 0 冻结") @NotNull @Min(0) @Max(1) Integer status
) {}
