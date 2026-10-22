package edu.czu.cinema.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 管理员登录请求. captcha 字段语义与 {@link edu.czu.cinema.domain.user.dto.UserLoginDTO} 同：
 * {@code cinema.captcha.required=true} 时 ticket+code 必填，缺失直接业务异常。
 */
@Schema(description = "管理员登录请求")
public record AdminLoginDTO(
        @Schema(description = "管理员名") @NotBlank String name,
        @Schema(description = "密码") @NotBlank String password,
        @Schema(description = "验证码票据(取图时返回)")
        @Size(max = 64) String captchaTicket,
        @Schema(description = "用户输入的验证码")
        @Size(max = 8) String captchaCode
) {}
