package edu.czu.cinema.domain.order.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 订单状态机（唯一事实源，禁止再散布魔法数）。
 * <pre>
 * 1 待支付 → 2 已支付 / 4 已关闭(超时/取消)
 * 2 已支付 → 3 已退款 / 6 旧已完成
 * 4 已关闭、3 已退款 为终态
 * </pre>
 * 状态 5 不是库内状态，仅表示列表查询「全部」，见 {@link #QUERY_ALL}。
 */
@Getter
@AllArgsConstructor
public enum OrderStatus {

    /** 待支付 */
    PENDING(1, "待支付"),
    /** 已支付 */
    PAID(2, "已支付"),
    /** 已退款 */
    REFUNDED(3, "已退款"),
    /** 已关闭（超时未付/用户取消/取消场次关单）——不是「退款中」 */
    CLOSED(4, "已关闭"),
    /** 旧版已完成（评价不再改变订单状态） */
    LEGACY_COMPLETED(6, "旧已完成");

    /** 列表查询哨兵：「全部」，不落库 */
    public static final int QUERY_ALL = 5;

    private final int code;
    private final String desc;

    public static boolean isPaidLike(Integer code) {
        return code != null && (code == PAID.code || code == LEGACY_COMPLETED.code);
    }

    public static boolean isPending(Integer code) {
        return code != null && code == PENDING.code;
    }
}
