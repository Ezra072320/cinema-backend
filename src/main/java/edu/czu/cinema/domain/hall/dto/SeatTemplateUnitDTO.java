package edu.czu.cinema.domain.hall.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 模板单元 DTO. 来自前端「整图覆盖」接口.
 *
 * <p>约定: status=0 也必须传(过道/留空), 后端按 y/x 唯一键整体替换.
 * label 可空.
 */
@Data
public class SeatTemplateUnitDTO {

    @NotNull
    @Min(1) @Max(50)
    private Integer y;

    @NotNull
    @Min(1) @Max(50)
    private Integer x;

    /** 0 无座位 / 1 标准 / 3 VIP / 4 无障碍. */
    @NotNull
    private Integer status;

    @Size(max = 16)
    private String label;
}
