package edu.czu.cinema.domain.ticket.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import edu.czu.cinema.domain.hall.entity.Seat;
import edu.czu.cinema.domain.schedule.entity.Schedule;
import edu.czu.cinema.domain.ticket.entity.Ticket;
import edu.czu.cinema.domain.ticket.mapper.TicketMapper;
import edu.czu.cinema.domain.ticket.service.TicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 票服务.
 *
 * <p>2026-10-20 起单表 SQL 全部改用 MyBatis-Plus 条件构造器(见 {@link TicketMapper} 的说明),
 * 并统一了「占用语义」: <strong>ticket.status=0 才算挂在订单上/算已售</strong>.
 * 取消场次作废(status=2)的票保留 order_id 供订单回看座位, 但不再计入已售 ——
 * 「取消后场次可删」与「已售数」因此口径一致.
 */
@Service
@RequiredArgsConstructor
public class TicketServiceImpl implements TicketService {

    /** 座位类型: 标准 / 情侣 / VIP. 与 Seat.type 的取值一致. */
    private static final int TYPE_STANDARD = 1;
    private static final int TYPE_COUPLE = 2;
    private static final int TYPE_VIP = 3;

    /** 票状态: 0 已被订单占用 / 1 可售 / 2 作废(取消场次)或已退. */
    private static final int STATUS_OCCUPIED = 0;
    private static final int STATUS_AVAILABLE = 1;
    private static final int STATUS_VOID = 2;

    /** order_id 的「未被任何订单占用」哨兵值(与 ticket 表默认值一致). */
    private static final long ORDER_ID_NONE = -1L;

    private final TicketMapper ticketMapper;

    @Override
    public List<Ticket> listByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ticketMapper.selectBatchIds(ids);
    }

    @Override
    public List<Ticket> listBySchedule(Long scheduleId) {
        if (scheduleId == null) return List.of();
        return ticketMapper.selectList(new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getScheduleId, scheduleId));
    }

    @Override
    public long countOrderedBySchedule(Long scheduleId) {
        if (scheduleId == null) return 0L;
        // 占用语义: 只数 status=0(被订单占着)的票.
        // 不再按 order_id!=-1 计数 —— 取消场次的作废票保留了 order_id(订单要能回看座位),
        // 若还数它们, 「取消后场次可删」的删除守卫就永远过不去; 作废票不算已售, 两边口径一致.
        Long n = ticketMapper.selectCount(new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getScheduleId, scheduleId)
                .eq(Ticket::getStatus, STATUS_OCCUPIED));
        return n == null ? 0L : n;
    }

    @Override
    @Transactional
    public int deleteBySchedule(Long scheduleId) {
        if (scheduleId == null) return 0;
        return ticketMapper.delete(new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getScheduleId, scheduleId));
    }

    @Override
    @Transactional
    public int voidBySchedule(Long scheduleId) {
        if (scheduleId == null) return 0;
        // 只作废, 不解绑: order_id 原样保留, 已退款订单还能回看当初的座位.
        // 早先这里 SET order_id=-1, 退款订单的历史座位就永久丢了.
        return ticketMapper.update(null, new LambdaUpdateWrapper<Ticket>()
                .eq(Ticket::getScheduleId, scheduleId)
                .set(Ticket::getStatus, STATUS_VOID));
    }

    /** 座位类型的定价倍率的兜底值, 场次上没配时用. */
    private static final BigDecimal DEFAULT_VIP_MULTIPLIER = new BigDecimal("3.00");
    private static final BigDecimal DEFAULT_COUPLE_MULTIPLIER = new BigDecimal("2.50");
    private static final BigDecimal DEFAULT_PRICE = new BigDecimal("50");

    /**
     * 为一场排片建票: 每个可售座位一条, 票价 = 基础票价 × 座位类型倍率.
     *
     * <p>倍率从**场次**上读({@code schedule.vipPriceMultiplier / couplePriceMultiplier}),
     * 不再硬编码在代码里 —— 这样午场和午夜场可以定不同的 VIP 价, 调价不用改代码发版.
     *
     * <p>票价在这里就算好落库. 之后改场次倍率**不会**回头改已建的票 —— 已经卖出去的
     * 票价格不该被后台改动.
     *
     * <p>乘积一律 {@code setScale(2, HALF_UP)} 再落库: 倍率乘出来的循环小数
     * (如 33.33 × 2.50 = 83.325) 直接写 DECIMAL(8,2) 会被 MySQL 按舍入规则截断,
     * 甚至超长直接报错 500 —— 精度与舍入在应用层定死, 和金额显示口径一致.
     */
    @Override
    @Transactional
    public void createTicketsForSchedule(Schedule schedule, List<Seat> seats) {
        BigDecimal base = schedule.getPrice() == null ? DEFAULT_PRICE : schedule.getPrice();
        for (Seat seat : seats) {
            Ticket t = new Ticket();
            t.setScheduleId(schedule.getId());
            t.setSeatId(seat.getId());
            t.setStatus(STATUS_AVAILABLE);
            t.setPrice(multiplierFor(schedule, seat).multiply(base).setScale(2, RoundingMode.HALF_UP));
            ticketMapper.insert(t);
        }
    }

    /** 该座位在这场排片下的倍率. */
    private static BigDecimal multiplierFor(Schedule schedule, Seat seat) {
        int type = seat.getType() == null ? TYPE_STANDARD : seat.getType();
        return switch (type) {
            case TYPE_COUPLE -> orDefault(schedule.getCouplePriceMultiplier(), DEFAULT_COUPLE_MULTIPLIER);
            case TYPE_VIP -> orDefault(schedule.getVipPriceMultiplier(), DEFAULT_VIP_MULTIPLIER);
            // 标准座(1) 和无障碍(4) 都是原价
            default -> BigDecimal.ONE;
        };
    }

    private static BigDecimal orDefault(BigDecimal v, BigDecimal fallback) {
        return (v == null || v.signum() <= 0) ? fallback : v;
    }

    @Override
    @Transactional
    public int lockTickets(List<Long> ticketIds, Long orderId) {
        // CAS: 条件 UPDATE(仅 status=1 可售的行才会被改到) + 返回受影响行数,
        // 由调用方与去重后的 ticketIds.size() 比对: 不足即部分锁(并发抢走一部分), 整单应回滚.
        // 这个「影响行数 == 请求数」的语义必须原样保留.
        return ticketMapper.update(null, new LambdaUpdateWrapper<Ticket>()
                .in(Ticket::getId, ticketIds)
                .eq(Ticket::getStatus, STATUS_AVAILABLE)
                .set(Ticket::getOrderId, orderId)
                .set(Ticket::getStatus, STATUS_OCCUPIED));
    }

    @Override
    @Transactional
    public boolean releaseTickets(Long orderId) {
        // 用户退票: 票回可售, order_id 回哨兵 -1. 语义保持不变 ——
        // 这是「主动退票释放座位」, 与取消场次的作废(voidBySchedule)是两条路径.
        return ticketMapper.update(null, new LambdaUpdateWrapper<Ticket>()
                .eq(Ticket::getOrderId, orderId)
                .set(Ticket::getStatus, STATUS_AVAILABLE)
                .set(Ticket::getOrderId, ORDER_ID_NONE)) > 0;
    }

    @Override
    public Long countSoldBySchedule(Long scheduleId) {
        // 已售 = 被订单占用(status=0). 作废票(status=2)不算已售 ——
        // 与 countOrderedBySchedule / 删除守卫同一口径.
        Long cnt = ticketMapper.selectCount(new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getScheduleId, scheduleId)
                .eq(Ticket::getStatus, STATUS_OCCUPIED));
        return cnt == null ? 0L : cnt;
    }

    @Override
    public List<Ticket> listByOrder(Long orderId) {
        return ticketMapper.selectList(new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getOrderId, orderId));
    }

    @Override
    public List<edu.czu.cinema.domain.ticket.vo.SeatStatusVO> listSeatStatusBySchedule(Long scheduleId) {
        return ticketMapper.selectList(new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getScheduleId, scheduleId))
                .stream()
                .map(t -> new edu.czu.cinema.domain.ticket.vo.SeatStatusVO(t.getSeatId(), t.getStatus()))
                .toList();
    }
}
