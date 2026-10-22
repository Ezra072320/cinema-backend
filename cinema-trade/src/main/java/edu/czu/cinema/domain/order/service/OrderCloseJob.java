package edu.czu.cinema.domain.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import edu.czu.cinema.domain.order.entity.Order;
import edu.czu.cinema.domain.order.enums.OrderStatus;
import edu.czu.cinema.domain.order.mapper.OrderMapper;
import edu.czu.cinema.domain.ticket.service.TicketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 待支付订单超时关单定时任务 + 孤儿票对账(P0-3) —— 2026-10-20 修复.
 *
 * <p>每分钟扫一次 status=1 且 create_time &lt; now - 15min 的订单,
 * 调 {@link OrderService#closeIfExpired} 条件置 4(已关闭)并 releaseTickets(独立事务原子提交).
 *
 * <p><b>对账扫描</b>: 顺带找「订单已进终态(3/4)但票仍 status=0 且挂在该单名下」的脏数据 ——
 * 历史版本关单两段非原子提交、或进程被杀夹在两步之间留下的孤儿票. 发现即补释放并告警,
 * 让选座页座位不再灰死. (修复后正常路径不会再产生这类数据, 扫的是存量与极端故障残留.)
 *
 * <p>与各业务入口(createOrder/pay/detail)的惰性校验双保险: 即使本任务被 scheduler
 * 阻塞或漏跑, 用户下次操作该笔订单时也会被自动关掉.
 *
 * <p><b>多实例部署注意</b>: 当前单实例无问题; 多实例需配 ShedLock, 防止重复关闭.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderCloseJob {

    private final OrderMapper orderMapper;
    private final OrderService orderService;
    private final TicketService ticketService;

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void sweep() {
        closeExpiredOrders();
        reconcileOrphanTickets();
    }

    private void closeExpiredOrders() {
        List<Order> expired = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .select(Order::getId)
                .eq(Order::getStatus, OrderStatus.PENDING.getCode())
                .lt(Order::getCreateTime, LocalDateTime.now().minusMinutes(OrderService.PAY_TIMEOUT_MINUTES))
                // 最老的先关: 兜底把积压单按等待时长排优先级, 用户等越久越该先释放座位
                .orderByAsc(Order::getCreateTime)
                .last("LIMIT 200")); // 单轮上限, 防积压时长事务
        if (expired.isEmpty()) return;
        int closed = 0;
        for (Order o : expired) {
            try {
                if (orderService.closeIfExpired(o.getId())) {
                    closed += 1;
                }
            } catch (Exception e) {
                // 单条失败不阻塞整轮. 该单仍是 status=1, 下一轮扫描会重试关闭, 只告警不吞.
                log.warn("[order-close-job] 关闭订单 id={} 失败(下一轮重试): {}", o.getId(), e.getMessage());
            }
        }
        if (closed > 0) {
            log.info("[order-close-job] 本轮关闭超期订单 {} 笔", closed);
        }
    }

    /**
     * 孤儿票对账: 订单已关/已退, 票却还锁在它名下(应已释放).
     * 用 EXISTS 子查询一次圈出脏数据, 不扫全表; LIMIT 防极端存量时长事务.
     */
    private void reconcileOrphanTickets() {
        List<Order> suspects = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .select(Order::getId, Order::getNumber)
                .in(Order::getStatus, OrderStatus.REFUNDED.getCode(), OrderStatus.CLOSED.getCode())
                // 票仍「已占用」且绑定在该单上 = 关单/退款时放票那步丢了
                .exists("SELECT 1 FROM ticket t WHERE t.order_id = `order`.id AND t.status = 0")
                .last("LIMIT 200"));
        for (Order o : suspects) {
            try {
                ticketService.releaseTickets(o.getId());
                log.warn("[order-close-job] 对账修复孤儿票: 订单 id={} number={} 已终态但票仍占用, 已补释放",
                        o.getId(), o.getNumber());
            } catch (Exception e) {
                log.warn("[order-close-job] 对账修复订单 id={} 失败(下一轮重试): {}", o.getId(), e.getMessage());
            }
        }
    }
}
