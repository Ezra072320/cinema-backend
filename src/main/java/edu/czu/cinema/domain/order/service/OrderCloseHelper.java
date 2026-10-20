package edu.czu.cinema.domain.order.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import edu.czu.cinema.domain.order.entity.Order;
import edu.czu.cinema.domain.order.entity.OrderAction;
import edu.czu.cinema.domain.order.enums.OrderStatus;
import edu.czu.cinema.domain.order.mapper.OrderMapper;
import edu.czu.cinema.domain.ticket.service.TicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 关单原子提交助手(P0-3) —— 「置 status=4」与「放票」必须同一事务一起成功或一起消失.
 *
 * <p>为什么不直接在 {@link OrderServiceImpl#closeIfExpired} 上加 @Transactional:
 * pay / createOrder / detail 内部都是<b>自调用</b> closeIfExpired, 走的是 this 而不是
 * Spring 代理, 事务注解不生效; 即便生效也是 REQUIRED 加入外层事务 ——
 * pay() 关单后紧接着抛「已超时关闭」, 外层一回滚把关单也回滚掉(P1-T3 的死逻辑).
 *
 * <p>所以拆出独立 bean 并用 {@link Propagation#REQUIRES_NEW}: 不管谁调用,
 * 「置 4 + 放票 + CLOSE 审计」都在自己的新事务里原子提交, 不受调用方失败/回滚影响.
 */
@Service
@RequiredArgsConstructor
public class OrderCloseHelper {

    private final OrderMapper orderMapper;
    private final TicketService ticketService;
    private final OrderAuditService orderAuditService;

    /**
     * 条件关单: 仅当订单仍是待支付(1)时置 4 并释放票.
     *
     * @param reason       关单原因, 落审计(超时关闭/用户取消, 原因不同但状态流转相同)
     * @param operatorType 操作者类型 {@link OrderAction#OP_USER} / {@link OrderAction#OP_SYSTEM}
     * @param operatorId   操作者 id, 系统动作传 null
     * @return true=本次确实关闭并释放了票; false=被并发抢先(已不在 status=1)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean closePending(Long orderId, String reason, String operatorType, Long operatorId) {
        Order before = orderMapper.selectById(orderId);
        // 条件 UPDATE: 仅当 status=1 时关闭. 并发下 pay/cancel 先得手就 0 行, 静默返回 false.
        int rows = orderMapper.update(null, new LambdaUpdateWrapper<Order>()
                .eq(Order::getId, orderId)
                .eq(Order::getStatus, OrderStatus.PENDING.getCode())
                .set(Order::getStatus, OrderStatus.CLOSED.getCode()));
        if (rows != 1) {
            return false;
        }
        // 与置 4 同事务: 任一步失败整段回滚, 不会再出现「单已关、票还锁着」的孤儿票
        ticketService.releaseTickets(orderId);
        orderAuditService.record(orderId,
                before == null ? "" : before.getNumber(),
                OrderAction.ACTION_CLOSE,
                OrderStatus.PENDING.getCode(), OrderStatus.CLOSED.getCode(),
                null, operatorType, operatorId, reason);
        return true;
    }
}
