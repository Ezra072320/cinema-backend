package edu.czu.cinema.domain.order;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import edu.czu.cinema.domain.order.entity.Order;
import edu.czu.cinema.domain.order.enums.OrderStatus;
import edu.czu.cinema.domain.order.mapper.OrderMapper;
import edu.czu.cinema.domain.order.service.OrderCloseJob;
import edu.czu.cinema.domain.order.service.OrderService;
import edu.czu.cinema.domain.ticket.service.TicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 超时关单任务 + 孤儿票对账(P0-3): 订单已关/已退而票仍占用 → 补释放.
 */
class OrderCloseJobTest {

    private OrderMapper orderMapper;
    private OrderService orderService;
    private TicketService ticketService;
    private OrderCloseJob job;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        orderService = mock(OrderService.class);
        ticketService = mock(TicketService.class);
        job = new OrderCloseJob(orderMapper, orderService, ticketService);
    }

    @Test
    void sweepClosesExpiredAndReconcilesOrphans() {
        // 第一遍扫描(超期关单): 无
        // 第二遍扫描(对账): 找到一个已退款但票仍挂在名下的脏单
        Order orphan = new Order();
        orphan.setId(55L);
        orphan.setNumber("202610201200000077123456");
        orphan.setStatus(OrderStatus.CLOSED.getCode());
        when(orderMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of())   // closeExpiredOrders: 无超期单
                .thenReturn(List.of(orphan)); // reconcileOrphanTickets: 命中脏单

        job.sweep();
        verify(orderService, never()).closeIfExpired(anyLong());
        verify(ticketService).releaseTickets(55L);  // 对账补放票
    }

    @Test
    void sweepReconcilesEverySuspectAndIsIsolatedPerFailure() {
        // 失败单条不阻断: 一个成功一个失败, 都各自处理不抛到外层
        Order a = new Order(); a.setId(1L);
        Order b = new Order(); b.setId(2L);
        when(orderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(), List.of(a, b));
        when(ticketService.releaseTickets(2L)).thenThrow(new RuntimeException("db down"));

        job.sweep(); // 不抛异常

        verify(ticketService).releaseTickets(1L);
        verify(ticketService).releaseTickets(2L);
    }
}