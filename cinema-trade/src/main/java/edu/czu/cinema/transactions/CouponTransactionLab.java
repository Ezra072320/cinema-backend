package edu.czu.cinema.transactions;

import edu.czu.cinema.common.util.ContextHolder;
import edu.czu.cinema.domain.order.dto.OrderAddDTO;
import edu.czu.cinema.domain.order.service.OrderService;
import edu.czu.cinema.remote.OperationJournal;
import org.apache.seata.spring.annotation.GlobalTransactional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Internal-only fault experiment, enabled exclusively by the local transactions overlay. */
@Service
@ConditionalOnProperty(name = "cinema.transactions.lab.enabled", havingValue = "true")
public class CouponTransactionLab {
  private final OrderService orders;
  private final OperationJournal journal;

  public CouponTransactionLab(OrderService orders, OperationJournal journal) {
    this.orders = orders;
    this.journal = journal;
  }

  @GlobalTransactional(
      name = "local-coupon-fault-experiment",
      timeoutMills = 15000,
      rollbackFor = Exception.class)
  public Long run(Long uid, OrderAddDTO dto, String key, int delay, boolean fail) throws Exception {
    if (dto.couponId() == null || delay < 0 || delay > 30000)
      throw new IllegalArgumentException("Invalid local experiment");
    ContextHolder.set(new ContextHolder.Principal(uid, "本地事务验收", "USER"));
    try {
      Long id =
          journal.run(key, uid, "CREATE_ORDER", dto, Long.class, () -> orders.createOrder(dto));
      if (delay > 0) Thread.sleep(delay);
      if (fail) throw new IllegalStateException("Requested local rollback experiment");
      return id;
    } finally {
      ContextHolder.clear();
    }
  }
}
