package edu.czu.cinema.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record PasswordResetDTO(
        @Schema(description = "原密码") @NotBlank String oldPassword,
        @Schema(description = "新密码")
        @NotBlank @Pattern(regexp = "^\\S{3,16}$") String newPassword,
        @Schema(description = "确认新密码") @NotBlank String confirmPassword
) {}
