package edu.czu.cinema.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AdminAddDTO(
        @Schema(description = "管理员名") @NotBlank @Pattern(regexp = "^\\S{2,16}$") String name,
        @Schema(description = "密码") @NotBlank @Pattern(regexp = "^\\S{3,16}$") String password,
        @Schema(description = "年龄") @Min(1) @Max(120) Integer age
) {}
