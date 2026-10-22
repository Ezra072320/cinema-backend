package edu.czu.cinema.domain.hall.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 新建模板的请求体.
 *
 * <p>新建时若不传 {@code units}, 会按 {@code rowsCount × colsCount} 全量生成 status=1
 * 的「全可用」模板, 方便管理员先建后改. 传了 {@code units} 就按传入内容生成.
 */
@Data
public class SeatTemplateCreateDTO {

    @NotBlank
    @Size(max = 64)
    private String name;

    @Size(max = 255)
    private String description;

    @NotNull
    @Min(1) @Max(50)
    private Integer rowsCount;

    @NotNull
    @Min(1) @Max(50)
    private Integer colsCount;

    private List<SeatTemplateUnitDTO> units;
}
