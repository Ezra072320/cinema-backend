package edu.czu.cinema.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 用户登录请求.
 *
 * <p>captchaTicket 与 captchaCode 由前端在取图时生成的 UUID + 用户输入组成。
 * 二者随登录请求一起提交，由 login 端点一次性校验并消费（票据无论对错都销毁）。
 * 当 {@code cinema.captcha.required=true}（默认）时二者必填，缺失直接业务异常——
 * 不再「留空跳过」；仅本地调试关掉开关后才允许不传。</p>
 */
@Schema(description = "用户登录请求")
public record UserLoginDTO(
        @Schema(description = "昵称") @NotBlank String name,
        @Schema(description = "密码") @NotBlank String password,
        @Schema(description = "验证码票据(取图时返回)")
        @Size(max = 64) String captchaTicket,
        @Schema(description = "用户输入的验证码")
        @Size(max = 8) String captchaCode
) {}
