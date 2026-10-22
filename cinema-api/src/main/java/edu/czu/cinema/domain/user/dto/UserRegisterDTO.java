package edu.czu.cinema.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 用户注册请求.
 *
 * <p>captcha 语义与 {@link UserLoginDTO} 一致：ticket+code 随注册请求提交，
 * 由 register 端点一次性校验并消费；{@code cinema.captcha.required=true} 时必填。
 * 管理端「新建用户」复用本 DTO 做入参校验（那里不校验 captcha，字段被忽略）。</p>
 */
@Schema(description = "用户注册请求")
public record UserRegisterDTO(
        @Schema(description = "昵称")
        @NotBlank
        @Pattern(regexp = "^\\S{2,16}$", message = "昵称长度需为 2~16 个非空字符")
        String name,

        @Schema(description = "密码")
        @NotBlank
        @Pattern(regexp = "^\\S{3,16}$", message = "密码长度需为 3~16 个非空字符")
        String password,

        @Schema(description = "年龄(可选,默认 0)")
        @Min(value = 1, message = "年龄需在 1~120 之间")
        @Max(value = 120, message = "年龄需在 1~120 之间")
        Integer age,

        @Schema(description = "验证码票据(取图时返回)")
        @Size(max = 64) String captchaTicket,

        @Schema(description = "用户输入的验证码")
        @Size(max = 8) String captchaCode
) {}
