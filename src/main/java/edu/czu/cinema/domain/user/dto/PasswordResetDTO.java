package edu.czu.cinema.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record PasswordResetDTO(
        @Schema(description = "原密码") @NotBlank String oldPassword,
        @Schema(description = "新密码")
        @NotBlank @Pattern(regexp = "^\\S{3,16}$", message = "密码长度需为 3~16 个非空字符") String newPassword,
        @Schema(description = "确认新密码") @NotBlank String confirmPassword
) {}
