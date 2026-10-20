package edu.czu.cinema.domain.ticket.controller;

import edu.czu.cinema.common.api.R;
import edu.czu.cinema.domain.ticket.service.TicketService;
import edu.czu.cinema.domain.ticket.vo.SeatStatusVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "07. 票务域")
@RestController
@RequestMapping("/api/ticket")
@RequiredArgsConstructor
public class TicketController {

    private final TicketService ticketService;

    /** 按排片 ID 获取座位状态列表（前端选座渲染）.
     *  旧的 /listByIds(POST) 端点已下线——前后端都无调用方, 也避免任意登录用户按 id 探测票与订单. */
    @Operation(summary = "按排片 ID 获取座位状态列表（前端选座渲染）")
    @GetMapping("/bySchedule/{scheduleId}")
    public R<List<SeatStatusVO>> seatStatus(@PathVariable Long scheduleId) {
        return R.ok(ticketService.listSeatStatusBySchedule(scheduleId));
    }
}