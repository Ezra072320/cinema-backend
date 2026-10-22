package edu.czu.cinema.domain.schedule.controller;

import edu.czu.cinema.common.api.PageResult;
import edu.czu.cinema.common.api.R;
import edu.czu.cinema.common.util.SecurityUtils;
import edu.czu.cinema.domain.schedule.dto.ScheduleAddDTO;
import edu.czu.cinema.domain.schedule.dto.ScheduleBatchAddDTO;
import edu.czu.cinema.domain.schedule.dto.ScheduleBatchMoveDTO;
import edu.czu.cinema.domain.schedule.dto.ScheduleUpdateDTO;
import edu.czu.cinema.domain.schedule.entity.Schedule;
import edu.czu.cinema.domain.schedule.service.ScheduleService;
import edu.czu.cinema.domain.schedule.vo.ScheduleBatchInfoVO;
import edu.czu.cinema.domain.schedule.vo.ScheduleBatchResultVO;
import edu.czu.cinema.domain.schedule.vo.ScheduleSeatsVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@Tag(name = "06. 排片域")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleService scheduleService;

    /**
     * status 参数: 不传/0=全部, 1=正常, 2=已取消。"已放映"不落库, 由 endTime 推导。
     */
    @Operation(summary = "排片分页（管理端）")
    @GetMapping("/admin/schedule/list")
    public R<PageResult<Schedule>> page(@RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "10") int limit,
                                       @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date,
                                       @RequestParam(required = false) Long hallId,
                                       @RequestParam(required = false) Long movieId,
                                       @RequestParam(required = false) Integer status) {
        SecurityUtils.requireAdminId();
        return R.ok(scheduleService.page(page, limit, date, hallId, movieId, status));
    }

    @Operation(summary = "新增排片")
    @PostMapping("/admin/schedule/add")
    public R<Long> add(@Valid @RequestBody ScheduleAddDTO dto) {
        SecurityUtils.requireAdminId();
        return R.ok(scheduleService.add(dto));
    }

    @Operation(summary = "批量排片（冲突/不合法的行跳过并报告原因，其余入库）")
    @PostMapping("/admin/schedule/batchAdd")
    public R<ScheduleBatchResultVO> batchAdd(@Valid @RequestBody ScheduleBatchAddDTO dto) {
        SecurityUtils.requireAdminId();
        return R.ok(scheduleService.batchAdd(dto));
    }

    @Operation(summary = "批量改期（同批次同时段整体改时刻，冲突/过期的跳过并报告原因）")
    @PostMapping("/admin/schedule/batchMove")
    public R<ScheduleBatchResultVO> batchMove(@Valid @RequestBody ScheduleBatchMoveDTO dto) {
        SecurityUtils.requireAdminId();
        return R.ok(scheduleService.batchMove(dto));
    }

    @Operation(summary = "批次同时段规模（total/movable，拖拽确认弹窗用）")
    @GetMapping("/admin/schedule/batchInfo/{batchId}")
    public R<ScheduleBatchInfoVO> batchInfo(@PathVariable String batchId,
                                            @RequestParam @org.springframework.format.annotation.DateTimeFormat(pattern = "HH:mm") java.time.LocalTime time) {
        SecurityUtils.requireAdminId();
        return R.ok(scheduleService.batchInfo(batchId, time));
    }

    @Operation(summary = "更新排片（不能改影厅、不能改价，要调价请取消后重排）")
    @PostMapping("/admin/schedule/update")
    public R<Boolean> update(@Valid @RequestBody ScheduleUpdateDTO dto) {
        SecurityUtils.requireAdminId();
        return R.ok(scheduleService.update(dto));
    }

    @Operation(summary = "取消场次（停止售票并自动全额退款，返回受影响订单数）")
    @PostMapping("/admin/schedule/cancel/{id}")
    public R<Integer> cancel(@PathVariable Long id) {
        SecurityUtils.requireAdminId();
        return R.ok(scheduleService.cancel(id));
    }

    @Operation(summary = "删除排片")
    @DeleteMapping("/admin/schedule/delete/{id}")
    public R<Boolean> delete(@PathVariable Long id) {
        SecurityUtils.requireAdminId();
        return R.ok(scheduleService.delete(id));
    }

    @Operation(summary = "按电影查询排片")
    @GetMapping("/schedule/byMovie/{movieId}")
    public R<List<Schedule>> byMovie(@PathVariable Long movieId) {
        return R.ok(scheduleService.listForMovie(movieId));
    }

    @Operation(summary = "按影厅公开列出排片（permitAll）")
    @GetMapping("/schedule/byHall/{hallId}")
    public R<List<Schedule>> publicByHall(@PathVariable Long hallId,
                                          @RequestParam(required = false) Long movieId) {
        return R.ok(scheduleService.publicByHall(hallId, movieId));
    }

    @Operation(summary = "排片详情（含 soldSeatIds 与 poster）")
    @GetMapping("/schedule/{id}")
    public R<Schedule> detail(@PathVariable Long id) {
        return R.ok(scheduleService.getById(id));
    }

    /**
     * 选座页数据: 场次信息 + 该场次锁定版本的座位图 + 每座的票状态.
     *
     * <p>公开端点(permitAll): 没登录也要能看座位图, 点「下单」时才要求登录.
     * 之前前端是从 {@code /schedule/{id}} 里读一个从来不存在的 {@code seats} 字段,
     * 所以选座页一直是空网格 —— 这个端点就是为了把它做实.
     */
    @Operation(summary = "选座数据（座位图 + 票状态）")
    @GetMapping("/schedule/{id}/seats")
    public R<ScheduleSeatsVO> seats(@PathVariable Long id) {
        return R.ok(scheduleService.seatsOfSchedule(id));
    }
}