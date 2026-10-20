package edu.czu.cinema.domain.captcha.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "图形验证码校验")
public record CaptchaCheckDTO(
        @Schema(description = "取图时返回的 ticket") @NotBlank String ticket,
        @Schema(description = "用户输入的验证码") @NotBlank String code
) {}
