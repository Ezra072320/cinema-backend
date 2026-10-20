package edu.czu.cinema.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 用户资料更新请求（自身更新与管理端编辑共用）.
 *
 * <p>只暴露可写白名单字段 name/age/pic/status——口令、createTime 等永不接受
 * 客户端写入，避免「编辑用户」接口顺带改口令/篡改创建时间（P1-S4）。
 * pic/status 仅供管理端编辑；自身更新只取 name/age，防止自助解冻。</p>
 *
 * <p>id 为管理端编辑时的目标用户；自身更新时忽略（身份始终取 JWT 里的不可变 ID）。
 * 另保留 (name, age) 便捷构造，供 AI 服务单等只改昵称/年龄的调用方使用。</p>
 */
public record UserUpdateDTO(
        @Schema(description = "目标用户 ID（仅管理端编辑使用；自身更新忽略）")
        Long id,

        @Schema(description = "新昵称")
        @Pattern(regexp = "^\\S{2,16}$", message = "昵称长度需为 2~16 个非空字符") String name,

        @Schema(description = "新年龄")
        @Min(1) @Max(120) Integer age,

        @Schema(description = "新头像 URL（仅管理端编辑）")
        @Size(max = 255) String pic,

        @Schema(description = "新状态：1 启用 / 0 冻结（仅管理端编辑；不传表示不改）")
        @Min(0) @Max(1) Integer status
) {
    /** 只改昵称/年龄的便捷构造（AI 服务单 UPDATE_PROFILE 等调用方使用）. */
    public UserUpdateDTO(String name, Integer age) {
        this(null, name, age, null, null);
    }
}
