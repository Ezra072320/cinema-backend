package edu.czu.cinema.domain.order;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import edu.czu.cinema.common.exception.BusinessException;
import edu.czu.cinema.common.util.SecurityUtils;
import edu.czu.cinema.domain.hall.mapper.HallMapper;
import edu.czu.cinema.domain.hall.service.SeatLabelService;
import edu.czu.cinema.domain.hall.service.SeatVersionService;
import edu.czu.cinema.domain.movie.mapper.MovieMapper;
import edu.czu.cinema.domain.order.dto.OrderAddDTO;
import edu.czu.cinema.domain.order.entity.Order;
import edu.czu.cinema.domain.order.entity.OrderAction;
import edu.czu.cinema.domain.order.enums.OrderStatus;
import edu.czu.cinema.domain.order.mapper.OrderMapper;
import edu.czu.cinema.domain.order.service.OrderAuditService;
import edu.czu.cinema.domain.order.service.OrderCloseHelper;
import edu.czu.cinema.domain.order.service.impl.OrderServiceImpl;
import edu.czu.cinema.domain.order.vo.OrderVO;
import edu.czu.cinema.domain.schedule.entity.Schedule;
import edu.czu.cinema.domain.schedule.mapper.ScheduleMapper;
import edu.czu.cinema.domain.schedule.service.ScheduleService;
import edu.czu.cinema.domain.score.mapper.ScoreMapper;
import edu.czu.cinema.domain.ticket.entity.Ticket;
import edu.czu.cinema.domain.ticket.service.TicketService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 下单/支付/退款一致性单测: 场次校验、防重复退款、支付落账与审计、超时拒付关单、订单号碰撞重试.
 */
class OrderServiceImplTest {

    private OrderMapper orderMapper;
    private ScheduleService scheduleService;
    private ScheduleMapper scheduleMapper;
    private TicketService ticketService;
    private SeatVersionService seatVersionService;
    private OrderAuditService auditService;
    private OrderCloseHelper closeHelper;
    private OrderServiceImpl service;
    /** createOrder 走 SecurityUtils.requireUserId() 取用户, 单测里静态 mock 掉. */
    private MockedStatic<SecurityUtils> security;

    @BeforeAll
    static void initMybatisLambdaCache() {
        // 纯 Mockito 单测没有 MyBatis 上下文, LambdaUpdateWrapper<Order> 解析列名时找不到 entity 缓存.
        // 这里手动给本测试 JVM 注册 Order 表缓存, 真实 service 里的 wrapper 才能正常 build SQL.
        TableInfoHelper.initTableInfo(
                new com.baomidou.mybatisplus.core.MybatisMapperBuilderAssistant(new MybatisConfiguration(), ""),
                Order.class);
    }

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        scheduleService = mock(ScheduleService.class);
        scheduleMapper = mock(ScheduleMapper.class);
        ticketService = mock(TicketService.class);
        seatVersionService = mock(SeatVersionService.class);
        auditService = mock(OrderAuditService.class);
        closeHelper = mock(OrderCloseHelper.class);
        // 座位标签解析(toVO 批量预取用; 默认空 map)
        SeatLabelService seatLabelService = mock(SeatLabelService.class);
        when(seatLabelService.labelsOf(any())).thenReturn(java.util.Collections.emptyMap());
        service = new OrderServiceImpl(orderMapper, scheduleService, scheduleMapper,
                ticketService, seatVersionService, seatLabelService,
                mock(ScoreMapper.class), mock(MovieMapper.class), mock(HallMapper.class),
                auditService, closeHelper);
        security = mockStatic(SecurityUtils.class);
        security.when(SecurityUtils::requireUserId).thenReturn(77L);
    }

    @AfterEach
    void tearDown() {
        security.close();
    }

    // 注: 3.5.9 的 TableInfoHelper 没有公开的 clear() 静态方法, 缓存只在本测试 JVM 进程内,
    // 对其它测试类无影响, 无需清理.

    private Schedule schedule(long id, int status, LocalDateTime start) {
        Schedule s = new Schedule();
        s.setId(id);
        s.setHallId(2L);
        s.setStatus(status);
        s.setStartTime(start);
        return s;
    }

    private Ticket ticket(long id, long scheduleId, int status) {
        Ticket t = new Ticket();
        t.setId(id);
        t.setScheduleId(scheduleId);
        t.setSeatId(id * 10);
        t.setStatus(status);
        t.setPrice(new BigDecimal("50"));
        return t;
    }

    private OrderAddDTO orderDto(long scheduleId, List<Long> ticketIds) {
        return new OrderAddDTO(scheduleId, ticketIds);
    }

    /** 造一单默认「本人 + 指定状态」的订单. */
    private Order order(long id, int status, long scheduleId) {
        Order o = new Order();
        o.setId(id);
        o.setUserId(77L);
        o.setStatus(status);
        o.setScheduleId(scheduleId);
        o.setNumber("202610201200000077123456");
        o.setTotalAmount(new BigDecimal("100.00"));
        o.setCreateTime(LocalDateTime.now());
        return o;
    }

    // ===== createOrder =====

    @Test
    void createOrderRejectsMissingSchedule() {
        when(scheduleMapper.selectById(9L)).thenReturn(null);
        assertThatThrownBy(() -> service.createOrder(orderDto(9L, List.of(1L))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("场次不存在");
        verifyNoInteractions(ticketService);
    }

    @Test
    void createOrderRejectsCanceledSchedule() {
        when(scheduleMapper.selectById(9L)).thenReturn(schedule(9L, 2, LocalDateTime.now().plusDays(1)));
        assertThatThrownBy(() -> service.createOrder(orderDto(9L, List.of(1L))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已取消");
    }

    @Test
    void createOrderRejectsStartedSchedule() {
        when(scheduleMapper.selectById(9L))
                .thenReturn(schedule(9L, 1, LocalDateTime.now().minusMinutes(5)));
        assertThatThrownBy(() -> service.createOrder(orderDto(9L, List.of(1L))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已开场");
    }

    @Test
    void createOrderRejectsForeignScheduleTickets() {
        when(scheduleMapper.selectById(9L)).thenReturn(schedule(9L, 1, LocalDateTime.now().plusDays(1)));
        // 票挂在另一个场次上
        when(ticketService.listByIds(List.of(5L))).thenReturn(List.of(ticket(5L, 8L, 1)));
        assertThatThrownBy(() -> service.createOrder(orderDto(9L, List.of(5L))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("票与场次不匹配");
    }

    @Test
    void createOrderRejectsTooManyTickets() {
        // 服务层断言 1~6 张(P1-T4 兜底, 不只依赖 DTO 注解)
        when(scheduleMapper.selectById(9L)).thenReturn(schedule(9L, 1, LocalDateTime.now().plusDays(1)));
        assertThatThrownBy(() -> service.createOrder(orderDto(9L, List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("一次最多购买 6 张");
        verifyNoInteractions(ticketService);
    }

    @Test
    void createOrderRejectsNonPositiveTotal() {
        when(orderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(scheduleMapper.selectById(9L)).thenReturn(schedule(9L, 1, LocalDateTime.now().plusDays(1)));
        Ticket free = ticket(5L, 9L, 1);
        free.setPrice(BigDecimal.ZERO);
        when(ticketService.listByIds(List.of(5L))).thenReturn(List.of(free));
        when(seatVersionService.coupleGroupsCovering(anySet())).thenReturn(List.of());
        assertThatThrownBy(() -> service.createOrder(orderDto(9L, List.of(5L))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("订单金额非法");
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    void createOrderLocksTicketsOnHappyPath() {
        // 惰性关单检查: 没有本人超期单
        when(orderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(scheduleMapper.selectById(9L)).thenReturn(schedule(9L, 1, LocalDateTime.now().plusDays(1)));
        when(ticketService.listByIds(List.of(5L, 6L)))
                .thenReturn(List.of(ticket(5L, 9L, 1), ticket(6L, 9L, 1)));
        when(orderMapper.insert(any(Order.class))).thenAnswer(inv -> {
            inv.getArgument(0, Order.class).setId(300L);
            return 1;
        });
        // lockTickets 返回 int(受影响行数); 锁到 2 张=全部锁住=成功
        when(ticketService.lockTickets(anyList(), eq(300L))).thenReturn(2);
        when(seatVersionService.coupleGroupsCovering(anySet())).thenReturn(List.of());

        Long orderId = service.createOrder(orderDto(9L, List.of(5L, 6L)));
        assertThat(orderId).isEqualTo(300L);
        verify(ticketService).lockTickets(List.of(5L, 6L), 300L);
        // P1-T1: 下单落 CREATE 审计, 金额=总额
        verify(auditService).record(eq(300L), anyString(), eq(OrderAction.ACTION_CREATE),
                isNull(), eq(OrderStatus.PENDING.getCode()), eq(new BigDecimal("100.00")),
                eq(OrderAction.OP_USER), eq(77L), anyString());
    }

    @Test
    void createOrderRetriesWhenOrderNumberCollides() {
        // P1-T5: order.number 有唯一键, 同秒随机段撞上抛 DuplicateKey → 换随机段重试而不是 500
        when(orderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(scheduleMapper.selectById(9L)).thenReturn(schedule(9L, 1, LocalDateTime.now().plusDays(1)));
        when(ticketService.listByIds(List.of(5L))).thenReturn(List.of(ticket(5L, 9L, 1)));
        when(seatVersionService.coupleGroupsCovering(anySet())).thenReturn(List.of());
        when(orderMapper.insert(any(Order.class)))
                .thenThrow(new DuplicateKeyException("uk_order_number"))
                .thenAnswer(inv -> {
                    inv.getArgument(0, Order.class).setId(302L);
                    return 1;
                });
        when(ticketService.lockTickets(anyList(), eq(302L))).thenReturn(1);

        Long orderId = service.createOrder(orderDto(9L, List.of(5L)));
        assertThat(orderId).isEqualTo(302L);
        verify(orderMapper, times(2)).insert(any(Order.class));
    }

    @Test
    void createOrderGivesUpAfterNumberRetries() {
        when(orderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(scheduleMapper.selectById(9L)).thenReturn(schedule(9L, 1, LocalDateTime.now().plusDays(1)));
        when(ticketService.listByIds(List.of(5L))).thenReturn(List.of(ticket(5L, 9L, 1)));
        when(seatVersionService.coupleGroupsCovering(anySet())).thenReturn(List.of());
        when(orderMapper.insert(any(Order.class))).thenThrow(new DuplicateKeyException("uk_order_number"));

        assertThatThrownBy(() -> service.createOrder(orderDto(9L, List.of(5L))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("订单号生成冲突");
        // 首次 + 3 次换随机段重试
        verify(orderMapper, times(4)).insert(any(Order.class));
        verify(ticketService, never()).lockTickets(anyList(), anyLong());
    }

    // ===== refund =====

    @Test
    void refundRejectsAlreadyRefunded() {
        // 2026-10-19 改造: 退款白名单仅 status=2; 已是 3(已退款) → "不可退款"
        Order o = new Order();
        o.setId(1L);
        o.setUserId(77L);
        o.setStatus(3);
        o.setScheduleId(9L);
        when(orderMapper.selectById(1L)).thenReturn(o);
        assertThatThrownBy(() -> service.refund(1L, 77L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不可退款");
        verify(ticketService, never()).releaseTickets(any());
    }

    @Test
    void refundRejectsUnpaidOrderAndSuggestsCancel() {
        // M2: 待支付单点"退款" → 引导去"取消订单", 释放票
        Order o = new Order();
        o.setId(2L); o.setUserId(77L); o.setStatus(1); o.setScheduleId(9L);
        when(orderMapper.selectById(2L)).thenReturn(o);
        assertThatThrownBy(() -> service.refund(2L, 77L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("取消订单");
        verify(ticketService, never()).releaseTickets(any());
    }

    @Test
    void refundFullAmountWritesRefundAndAudit() {
        // 距开场 >6h: 全额退 100.00
        Order o = order(10L, 2, 9L);
        when(orderMapper.selectById(10L)).thenReturn(o);
        when(scheduleService.getById(9L)).thenReturn(schedule(9L, 1, LocalDateTime.now().plusHours(7)));
        when(orderMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        String msg = service.refund(10L, 77L);
        assertThat(msg).contains("全额退款").contains("100.00");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<Order>> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(orderMapper).update(any(), captor.capture());
        assertThat(captor.getValue().getSqlSet()).contains("refund_amount").contains("refund_time");
        assertThat(captor.getValue().getParamNameValuePairs().values())
                .anyMatch(v -> v instanceof BigDecimal bd && bd.compareTo(new BigDecimal("100.00")) == 0);
        verify(ticketService).releaseTickets(10L);
        verify(auditService).record(eq(10L), anyString(), eq(OrderAction.ACTION_REFUND),
                eq(OrderStatus.PAID.getCode()), eq(OrderStatus.REFUNDED.getCode()),
                eq(new BigDecimal("100.00")), eq(OrderAction.OP_USER), eq(77L), contains("全额"));
    }

    @Test
    void refundSeventyPercentInThreeToSixHourWindow() {
        // 距开场 4h → 70% 档: 100.00 × 0.7 = 70.00, 审计金额=实退额, reason 注明档位
        Order o = order(11L, 2, 9L);
        when(orderMapper.selectById(11L)).thenReturn(o);
        when(scheduleService.getById(9L)).thenReturn(schedule(9L, 1, LocalDateTime.now().plusHours(4)));
        when(orderMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        String msg = service.refund(11L, 77L);
        assertThat(msg).contains("70%").contains("70.00");

        verify(auditService).record(eq(11L), anyString(), eq(OrderAction.ACTION_REFUND),
                eq(OrderStatus.PAID.getCode()), eq(OrderStatus.REFUNDED.getCode()),
                eq(new BigDecimal("70.00")), eq(OrderAction.OP_USER), eq(77L), contains("70%"));
        verify(ticketService).releaseTickets(11L);
    }

    @Test
    void refundRejectsWhenExpectedAmountMismatch() {
        // AI 确认单带着旧档位金额来 → 金额已变化拒绝, 不动库
        Order o = order(12L, 2, 9L);
        when(orderMapper.selectById(12L)).thenReturn(o);
        when(scheduleService.getById(9L)).thenReturn(schedule(9L, 1, LocalDateTime.now().plusHours(4)));

        assertThatThrownBy(() -> service.refund(12L, 77L, new BigDecimal("12.34")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("退款金额已变化");
        verify(orderMapper, never()).update(any(), any(LambdaUpdateWrapper.class));
        verifyNoInteractions(auditService);
    }

    // ===== pay =====

    @Test
    void payRejectsInvalidChannel() {
        assertThatThrownBy(() -> service.pay(1L, "BITCOIN", 77L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("非法支付渠道");
    }

    @Test
    void payRejectsNonPendingOrder() {
        Order o = order(20L, 2, 9L);
        when(orderMapper.selectById(20L)).thenReturn(o);
        assertThatThrownBy(() -> service.pay(20L, "WECHAT", 77L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不可支付");
        verify(orderMapper, never()).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void payClosesExpiredOrderThenRejects() {
        // P1-T3: 超期先关(独立事务)再拒付 —— 关单不被 pay 的异常回滚
        Order o = order(21L, 1, 9L);
        o.setCreateTime(LocalDateTime.now().minusMinutes(20));
        when(orderMapper.selectById(21L)).thenReturn(o);
        when(closeHelper.closePending(eq(21L), anyString(), anyString(), any())).thenReturn(true);

        assertThatThrownBy(() -> service.pay(21L, "WECHAT", 77L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已超时关闭");
        // 关单走 REQUIRES_NEW 助手, 且不在 pay 事务里
        verify(closeHelper).closePending(eq(21L), anyString(), eq(OrderAction.OP_SYSTEM), isNull());
        // 拒付路径绝不落 PAY 审计
        verify(auditService, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void payRejectsWhenScheduleCanceled() {
        Order o = order(22L, 1, 9L);
        when(orderMapper.selectById(22L)).thenReturn(o);
        when(scheduleMapper.selectByIdForUpdate(9L)).thenReturn(schedule(9L, 2, LocalDateTime.now().plusDays(1)));
        assertThatThrownBy(() -> service.pay(22L, "WECHAT", 77L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("场次已取消");
        verify(orderMapper, never()).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void payRollsBackWhenScheduleCanceledAfterCas() {
        // P0-4 配合点: CAS 成功后复核场次, 取消场次事务刚提交的取消能被看见 → 抛异常回滚 pay
        Order o = order(23L, 1, 9L);
        when(orderMapper.selectById(23L)).thenReturn(o);
        when(scheduleMapper.selectByIdForUpdate(9L))
                .thenReturn(schedule(9L, 1, LocalDateTime.now().plusDays(1)));  // 支付前锁定读校验通过
        when(scheduleMapper.selectById(9L))
                .thenReturn(schedule(9L, 2, LocalDateTime.now().plusDays(1)));  // CAS 后复核: 已取消
        when(orderMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        assertThatThrownBy(() -> service.pay(23L, "WECHAT", 77L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("支付未生效");
        verify(auditService, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void paySuccessWritesPayTimeChannelAndAudit() {
        Order o = order(24L, 1, 9L);
        when(orderMapper.selectById(24L)).thenReturn(o);
        when(scheduleMapper.selectByIdForUpdate(9L)).thenReturn(schedule(9L, 1, LocalDateTime.now().plusDays(1)));
        when(scheduleMapper.selectById(9L)).thenReturn(schedule(9L, 1, LocalDateTime.now().plusDays(1)));
        when(orderMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        OrderVO vo = service.pay(24L, "wechat", 77L);
        // 轻量 VO: 只带支付页要的字段, 不再在行锁事务里全量组装
        assertThat(vo.getId()).isEqualTo(24L);
        assertThat(vo.getNumber()).isEqualTo("202610201200000077123456");
        assertThat(vo.getStatus()).isEqualTo(OrderStatus.PAID.getCode());
        assertThat(vo.getTotalAmount()).isEqualByComparingTo("100.00");
        assertThat(vo.getMovieName()).isNull();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<Order>> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(orderMapper).update(any(), captor.capture());
        // P1-T1: pay_time / pay_channel 与状态同条 UPDATE 落库
        assertThat(captor.getValue().getSqlSet()).contains("pay_time").contains("pay_channel");
        assertThat(captor.getValue().getParamNameValuePairs().values()).contains("WECHAT");
        verify(auditService).record(eq(24L), anyString(), eq(OrderAction.ACTION_PAY),
                eq(OrderStatus.PENDING.getCode()), eq(OrderStatus.PAID.getCode()),
                eq(new BigDecimal("100.00")), eq(OrderAction.OP_USER), eq(77L), contains("WECHAT"));
    }

    @Test
    void payRejectsWhenCasLoses() {
        // 并发下别人先付/先取消: 条件 UPDATE 0 行
        Order o = order(25L, 1, 9L);
        when(orderMapper.selectById(25L)).thenReturn(o);
        when(scheduleMapper.selectByIdForUpdate(9L)).thenReturn(schedule(9L, 1, LocalDateTime.now().plusDays(1)));
        when(orderMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(0);

        assertThatThrownBy(() -> service.pay(25L, "WECHAT", 77L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("状态已变化");
    }

    // ===== detail (C2 ownership / 惰性关单) =====

    @Test
    void detailRejectsForeignOrder() {
        Order o = new Order();
        o.setId(5L);
        o.setUserId(77L);
        o.setStatus(2);
        o.setScheduleId(9L);
        when(orderMapper.selectById(5L)).thenReturn(o);
        // 用别人 userId 查
        assertThatThrownBy(() -> service.detail(5L, 99L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不属于当前用户");
    }

    @Test
    void detailLazilyClosesExpiredPendingOrder() {
        Order pending = order(30L, 1, 9L);
        pending.setCreateTime(LocalDateTime.now().minusMinutes(30));
        Order closed = order(30L, OrderStatus.CLOSED.getCode(), 9L);
        closed.setCreateTime(pending.getCreateTime());
        when(orderMapper.selectById(30L)).thenReturn(pending, closed);
        when(closeHelper.closePending(eq(30L), anyString(), anyString(), any())).thenReturn(true);

        OrderVO vo = service.detail(30L, 77L);
        verify(closeHelper).closePending(eq(30L), anyString(), eq(OrderAction.OP_SYSTEM), isNull());
        assertThat(vo.getStatus()).isEqualTo(OrderStatus.CLOSED.getCode());
    }

    // ===== createOrder 部分锁回滚 (C4) =====

    @Test
    void createOrderRollsBackOnPartialLock() {
        when(orderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(scheduleMapper.selectById(9L)).thenReturn(schedule(9L, 1, LocalDateTime.now().plusDays(1)));
        when(ticketService.listByIds(List.of(5L, 6L)))
                .thenReturn(List.of(ticket(5L, 9L, 1), ticket(6L, 9L, 1)));
        when(orderMapper.insert(any(Order.class))).thenAnswer(inv -> {
            inv.getArgument(0, Order.class).setId(301L);
            return 1;
        });
        // 并发抢走一张: 只锁到 1 张
        when(ticketService.lockTickets(anyList(), eq(301L))).thenReturn(1);
        when(seatVersionService.coupleGroupsCovering(anySet())).thenReturn(List.of());
        assertThatThrownBy(() -> service.createOrder(orderDto(9L, List.of(5L, 6L))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("被抢了一部分");
        verify(auditService, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ===== 取消订单 =====

    @Test
    void cancelOnlyWorksOnPendingOrder() {
        Order o = new Order();
        o.setId(8L); o.setUserId(77L); o.setStatus(2); o.setScheduleId(9L);  // 已支付
        when(orderMapper.selectById(8L)).thenReturn(o);
        assertThatThrownBy(() -> service.cancel(8L, 77L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不可取消");
        verify(ticketService, never()).releaseTickets(any());
    }

    @Test
    void cancelPendingClosesAndReleases() {
        Order o = new Order();
        o.setId(9L); o.setUserId(77L); o.setStatus(1); o.setScheduleId(9L);
        when(orderMapper.selectById(9L)).thenReturn(o);
        when(closeHelper.closePending(eq(9L), anyString(), anyString(), any())).thenReturn(true);
        String r = service.cancel(9L, 77L);
        assertThat(r).contains("取消").contains("释放");
        // 用户取消与超时关单同一原子关单路径, 仅原因/操作者不同
        verify(closeHelper).closePending(eq(9L), eq("用户取消订单"), eq(OrderAction.OP_USER), eq(77L));
    }

    // ===== closeIfExpired / countByStatus =====

    @Test
    void closeIfExpiredDelegatesToAtomicHelper() {
        when(closeHelper.closePending(eq(40L), anyString(), anyString(), any())).thenReturn(true);
        assertThat(service.closeIfExpired(40L)).isTrue();
        verify(closeHelper).closePending(eq(40L), anyString(), eq(OrderAction.OP_SYSTEM), isNull());
    }

    @Test
    void countByStatusUsesSingleGroupByQuery() {
        when(orderMapper.selectMaps(any())).thenReturn(List.of(
                java.util.Map.of("status", 1, "cnt", 2L),
                java.util.Map.of("status", 4, "cnt", 1L)));
        var result = service.countByStatus(77L);
        assertThat(result).containsEntry(1, 2L).containsEntry(4, 1L).doesNotContainKey(3);
        // 一条 GROUP BY, 不再按状态逐个 selectCount
        verify(orderMapper, times(1)).selectMaps(any());
        verify(orderMapper, never()).selectCount(any());
    }
}
