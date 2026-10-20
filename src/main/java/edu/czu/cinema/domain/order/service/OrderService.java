package edu.czu.cinema.domain.order.service;

import edu.czu.cinema.common.api.PageResult;
import edu.czu.cinema.domain.order.dto.OrderAddDTO;
import edu.czu.cinema.domain.order.vo.OrderVO;

import java.util.List;
import java.util.Map;

public interface OrderService {

    /** 待支付订单超时阈值（分钟）—— 前端倒计时与后端扫描/惰性校验共用同一值. */
    int PAY_TIMEOUT_MINUTES = 15;

    Long createOrder(OrderAddDTO dto);

    /** 详情查询，必须传当前用户 id; 越权抛 FORBIDDEN. */
    OrderVO detail(Long orderId, Long currentUserId);

    /** 兼容旧签名: 不分页, 全量返回(前端已对接的默认行为). */
    List<OrderVO> listByUser(Long userId, Integer status);

    /**
     * 可选分页的订单列表(page/limit 任一为空即全量, 与旧签名行为一致).
     * 前端想分页时才传, 旧请求不受影响.
     */
    List<OrderVO> listByUser(Long userId, Integer status, Integer page, Integer limit);

    String refund(Long orderId, Long currentUserId);

    /** AI 确认单携带已审核金额，跨退款档位时拒绝按新金额执行。 */
    String refund(Long orderId, Long currentUserId, java.math.BigDecimal expectedAmount);

    OrderVO pay(Long orderId, String payChannel, Long currentUserId);

    /** 用户主动取消待支付订单: 仅 status=1 可取消, 置 4 并释放票. */
    String cancel(Long orderId, Long currentUserId);

    /**
     * 条件关闭: 仅当订单仍是待支付(1)时置 4 并释放票.
     * 供定时扫描与各业务入口惰性调用.
     * 「置 4 + 放票」走 REQUIRES_NEW 独立事务原子提交, 不受调用方事务回滚影响.
     * @return true=本次确实关闭并释放了票; false=被并发抢先(已不在 status=1).
     */
    boolean closeIfExpired(Long orderId);

    /** 当前用户订单状态计数（用于个人中心 tab 角标） */
    Map<Integer, Long> countByStatus(Long userId);

    /** 后台订单分页查询. */
    PageResult<OrderVO> adminPage(int page, int size, Integer status, String keyword);
}
