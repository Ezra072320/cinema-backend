package edu.czu.cinema.domain.hall.controller;

import edu.czu.cinema.common.api.R;
import edu.czu.cinema.common.util.SecurityUtils;
import edu.czu.cinema.domain.hall.entity.Hall;
import edu.czu.cinema.domain.hall.entity.Seat;
import edu.czu.cinema.domain.hall.service.HallService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "05. 影厅域")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class HallController {

    private final HallService hallService;

    @Operation(summary = "全部影厅")
    @GetMapping("/hall/list")
    public R<List<Hall>> list() {
        return R.ok(hallService.listAll());
    }

    @Operation(summary = "影厅详情（包含座位与情侣对）")
    @GetMapping("/hall/{id}")
    public R<Hall> detail(@PathVariable Long id) {
        Hall h = hallService.getById(id);
        if (h != null) {
            h.setSeats(hallService.seatsOf(id));
            // 情侣对一并下发: 前端要把一对的两个格子合并显示, 而"猜"是猜不准的
            // (一排里连着两对情侣座时, 靠相邻的 type=2 分不出边界)
            h.setCoupleGroups(hallService.coupleGroupsOf(id));
        }
        return R.ok(h);
    }

    @Operation(summary = "新增影厅")
    @PostMapping("/admin/hall/add")
    public R<Long> add(@RequestBody Hall hall) {
        SecurityUtils.requireAdminId();
        return R.ok(hallService.add(hall));
    }

    @Operation(summary = "更新影厅及其座位")
    @PostMapping("/admin/hall/update")
    public R<Hall> update(@RequestBody Hall hall) {
        SecurityUtils.requireAdminId();
        return R.ok(hallService.updateWithSeats(hall));
    }

    @Operation(summary = "删除影厅")
    @DeleteMapping("/admin/hall/delete/{id}")
    public R<Boolean> delete(@PathVariable Long id) {
        SecurityUtils.requireAdminId();
        return R.ok(hallService.delete(id));
    }

    @Operation(summary = "座位列表")
    @GetMapping("/hall/seats/{hallId}")
    public R<List<Seat>> seatsOf(@PathVariable Long hallId) {
        return R.ok(hallService.seatsOf(hallId));
    }
}
