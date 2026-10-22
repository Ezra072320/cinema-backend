package edu.czu.cinema.domain.order.service;

import edu.czu.cinema.domain.order.enums.OrderStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;

/** 实际退款与 AI 预估共用同一规则，包括整分钟边界和金额舍入。 */
public final class OrderRefundPolicy {
    private OrderRefundPolicy() {}
    public record Quote(boolean eligible, BigDecimal amount, String reason) {}

    public static Quote quote(Integer status, BigDecimal total, LocalDateTime start, LocalDateTime now) {
        // 仅「已支付(2)」可退: 旧已完成(6)与已退/已关都是终态, 一律不进退款档位
        if (status == null || status != OrderStatus.PAID.getCode())
            return new Quote(false, BigDecimal.ZERO, "仅已支付订单可退款，待支付请取消订单");
        if (start == null) return new Quote(false, BigDecimal.ZERO, "场次时间缺失，请联系影院工作人员");
        long minutes = Duration.between(now, start).toMinutes();
        if (minutes < 180) return new Quote(false, BigDecimal.ZERO, "距开场不足3小时或已开场，不可退款");
        if (minutes > 360) return new Quote(true, total, "全额退款");
        return new Quote(true, total.multiply(new BigDecimal("0.7")).setScale(2, RoundingMode.HALF_UP),
                "距开场3至6小时，按70%退款");
    }
}
