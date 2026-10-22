package edu.czu.cinema.domain.hall.controller;

import edu.czu.cinema.common.api.R;
import edu.czu.cinema.common.util.SecurityUtils;
import edu.czu.cinema.domain.hall.dto.SeatTemplateCreateDTO;
import edu.czu.cinema.domain.hall.dto.SeatTemplateReplaceUnitsDTO;
import edu.czu.cinema.domain.hall.entity.SeatTemplate;
import edu.czu.cinema.domain.hall.service.SeatTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 座位模板管理.
 *
 * <p>管理员后台用. Phase 1 只暴露最基础的 CRUD:
 * <ul>
 *   <li>列表 / 详情</li>
 *   <li>新建(可选择带初始 units)</li>
 *   <li>整图覆盖 units</li>
 *   <li>删除</li>
 * </ul>
 * <p>注意: 模板本身是「草图」, 改/删模板都不会影响影厅已有座位 —— 那是 Phase 2+ 的事.
 */
@Tag(name = "06. 影厅座位模板")
@RestController
@RequestMapping("/api/admin/seat-template")
@RequiredArgsConstructor
public class SeatTemplateAdminController {

    private final SeatTemplateService seatTemplateService;

    @Operation(summary = "模板列表(带单元数)")
    @GetMapping
    public R<List<SeatTemplate>> list() {
        SecurityUtils.requireAdminId();
        return R.ok(seatTemplateService.listTemplates());
    }

    @Operation(summary = "模板详情(含 units)")
    @GetMapping("/{id}")
    public R<SeatTemplate> detail(@PathVariable Long id) {
        SecurityUtils.requireAdminId();
        return R.ok(seatTemplateService.getById(id));
    }

    @Operation(summary = "新建模板")
    @PostMapping
    public R<Long> create(@Valid @RequestBody SeatTemplateCreateDTO dto) {
        SecurityUtils.requireAdminId();
        return R.ok(seatTemplateService.create(dto));
    }

    @Operation(summary = "整图覆盖模板单元")
    @PutMapping("/{id}/units")
    public R<Void> replaceUnits(@PathVariable Long id,
                                @Valid @RequestBody SeatTemplateReplaceUnitsDTO dto) {
        SecurityUtils.requireAdminId();
        seatTemplateService.replaceUnits(id, dto);
        return R.ok();
    }

    @Operation(summary = "删除模板")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        SecurityUtils.requireAdminId();
        seatTemplateService.delete(id);
        return R.ok();
    }
}
