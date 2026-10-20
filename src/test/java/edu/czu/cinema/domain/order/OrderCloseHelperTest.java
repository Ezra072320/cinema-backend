package edu.czu.cinema.domain.order;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import edu.czu.cinema.domain.order.entity.Order;
import edu.czu.cinema.domain.order.entity.OrderAction;
import edu.czu.cinema.domain.order.enums.OrderStatus;
import edu.czu.cinema.domain.order.mapper.OrderMapper;
import edu.czu.cinema.domain.order.service.OrderAuditService;
import edu.czu.cinema.domain.order.service.OrderCloseHelper;
import edu.czu.cinema.domain.ticket.service.TicketService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 关单原子性单测(P0-3): 「置 4」与「放票」同事务, 且必须 REQUIRES_NEW 独立于调用方.
 */
class OrderCloseHelperTest {

    private OrderMapper orderMapper;
    private TicketService ticketService;
    private OrderAuditService auditService;
    private OrderCloseHelper helper;

    @BeforeAll
    static void initMybatisLambdaCache() {
        // LambdaUpdateWrapper<Order> 解析列名需要 Order 表缓存
        TableInfoHelper.initTableInfo(
                new com.baomidou.mybatisplus.core.MybatisMapperBuilderAssistant(new MybatisConfiguration(), ""),
                Order.class);
    }

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        ticketService = mock(TicketService.class);
        auditService = mock(OrderAuditService.class);
        helper = new OrderCloseHelper(orderMapper, ticketService, auditService);
    }

    private Order order(long id) {
        Order o = new Order();
        o.setId(id);
        o.setUserId(77L);
        o.setStatus(OrderStatus.PENDING.getCode());
        o.setNumber("202610201200000077123456");
        return o;
    }

    @Test
    void closesAndReleasesInOneShot() {
        when(orderMapper.selectById(9L)).thenReturn(order(9L));
        when(orderMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        assertThat(helper.closePending(9L, "用户取消订单", OrderAction.OP_USER, 77L)).isTrue();

        // 置 4 与放票都在 closePending 内完成 —— 同一(REQUIRES_NEW)事务里原子提交
        verify(ticketService).releaseTickets(9L);
        verify(auditService).record(eq(9L), eq("202610201200000077123456"), eq(OrderAction.ACTION_CLOSE),
                eq(OrderStatus.PENDING.getCode()), eq(OrderStatus.CLOSED.getCode()),
                isNull(), eq(OrderAction.OP_USER), eq(77L), eq("用户取消订单"));
    }

    @Test
    void skipsReleaseWhenLostRace() {
        // 条件 UPDATE 0 行 = 并发下 pay/cancel 先得手: 不放票、不审计
        when(orderMapper.selectById(9L)).thenReturn(order(9L));
        when(orderMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(0);

        assertThat(helper.closePending(9L, "超时未支付，系统自动关闭", OrderAction.OP_SYSTEM, null)).isFalse();
        verify(ticketService, never()).releaseTickets(any());
        verify(auditService, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void releaseFailurePropagatesSoTransactionRollsBack() {
        // 放票失败必须向上传播 —— 事务把「置 4」一并回滚, 宁可留着待支付单, 不留孤儿票
        when(orderMapper.selectById(9L)).thenReturn(order(9L));
        when(orderMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(ticketService.releaseTickets(9L)).thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> helper.closePending(9L, "超时未支付，系统自动关闭", OrderAction.OP_SYSTEM, null))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void mustRunInRequiresNewPropagation() throws Exception {
        // P1-T3 的关键约束: REQUIRES_NEW 让关单不被 pay/createOrder 外层事务的回滚带走
        Method m = OrderCloseHelper.class.getMethod("closePending", Long.class, String.class, String.class, Long.class);
        Transactional tx = m.getAnnotation(Transactional.class);
        assertThat(tx).as("closePending 必须带 @Transactional").isNotNull();
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(tx.rollbackFor()).contains(Exception.class);
    }
}
