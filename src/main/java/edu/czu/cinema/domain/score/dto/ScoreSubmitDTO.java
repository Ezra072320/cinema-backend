package edu.czu.cinema.domain.score.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ScoreSubmitDTO(
        @Schema(description = "订单 ID（须属于当前登录用户）") @NotNull @Min(1) Long orderId,
        @Schema(description = "评分 1-5") @NotNull @Min(1) @Max(5) Integer score,
        @Schema(description = "评论") @Size(max = 1024) String content,
        @Schema(description = "新评价为0；修改时使用读取到的version，防止覆盖其他窗口修改") @Min(0) Long expectedVersion
) {
    public ScoreSubmitDTO(Long orderId, Integer score, String content) {
        this(orderId, score, content, null);
    }
}
