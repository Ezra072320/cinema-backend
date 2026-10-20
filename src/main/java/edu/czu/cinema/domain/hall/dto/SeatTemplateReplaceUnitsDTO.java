package edu.czu.cinema.domain.hall.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 「整图覆盖」模板单元的请求体. 前端编辑完整个座位图后一次性传过来.
 *
 * <p>整图覆盖而非增量: 模板是草图, 没有版本, 直接 delete + batch insert 即可.
 * 真要加增量, 留到 Phase 7+ 再考虑.
 */
@Data
public class SeatTemplateReplaceUnitsDTO {

    @NotNull
    private List<SeatTemplateUnitDTO> units;
}
