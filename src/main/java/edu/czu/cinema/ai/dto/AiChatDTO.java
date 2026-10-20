package edu.czu.cinema.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 流式对话请求体.
 *
 * <p>2026-10-19 由 GET query 改为 POST body: 超长问题会顶爆 URL 上限,
 * 且提问内容会原样进访问日志.
 */
@Schema(description = "AI 对话")
public record AiChatDTO(
        @Schema(description = "用户提问")
        @NotBlank(message = "问题不能为空")
        @Size(max = 4000, message = "问题过长")
        String question,

        @Schema(description = "会话ID(前端生成的 UUID)")
        @NotBlank(message = "chatId 不能为空")
        @Size(max = 64, message = "chatId 过长")
        String chatId
) {
}
