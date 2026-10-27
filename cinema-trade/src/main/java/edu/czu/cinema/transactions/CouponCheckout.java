package edu.czu.cinema.transactions;

import edu.czu.cinema.common.exception.BusinessException;
import edu.czu.cinema.domain.order.dto.OrderAddDTO;
import edu.czu.cinema.domain.order.service.OrderService;
import edu.czu.cinema.remote.OperationJournal;
import org.apache.seata.spring.annotation.GlobalTransactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Global transaction wraps the LOCAL journal/order transaction, never the opposite order. */
@Service
public class CouponCheckout {
  private final OrderService orders;
  private final OperationJournal journal;
  private final boolean enabled;

  public CouponCheckout(
      OrderService orders,
      OperationJournal journal,
      @Value("${seata.enabled:false}") boolean enabled) {
    this.orders = orders;
    this.journal = journal;
    this.enabled = enabled;
  }

  @GlobalTransactional(
      name = "coupon-checkout",
      timeoutMills = 15000,
      rollbackFor = Exception.class)
  public Long create(Long uid, OrderAddDTO dto, String key) {
    if (!enabled) throw new BusinessException("优惠券服务尚未启用，请启动本地事务组");
    if (key == null || key.isBlank()) throw new BusinessException("使用优惠券需要有效操作编号");
    return journal.run(key, uid, "CREATE_ORDER", dto, Long.class, () -> orders.createOrder(dto));
  }
}
