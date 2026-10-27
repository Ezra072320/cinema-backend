package edu.czu.cinema.transactions;

import edu.czu.cinema.common.exception.BusinessException;
import edu.czu.cinema.domain.benefit.CouponContracts.Reserve;
import edu.czu.cinema.domain.order.entity.Order;
import edu.czu.cinema.domain.order.vo.OrderVO;
import edu.czu.cinema.remote.BenefitClient;
import org.apache.seata.core.context.RootContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class CouponOrderSupport {
  private final JdbcTemplate jdbc;
  private final BenefitClient benefit;
  private final GlobalOutboxFence fence;

  public CouponOrderSupport(JdbcTemplate jdbc, BenefitClient benefit, GlobalOutboxFence fence) {
    this.jdbc = jdbc;
    this.benefit = benefit;
    this.fence = fence;
  }

  public void reserve(Order order, Long couponId) {
    if (RootContext.getXID() == null) throw new BusinessException("优惠券服务尚未启用，请不使用优惠券或启动事务组");
    var original = order.getTotalAmount();
    var d = benefit.reserve(new Reserve(order.getUserId(), couponId, order.getId(), original));
    if (!couponId.equals(d.couponId())
        || d.discount().signum() <= 0
        || d.payable().signum() <= 0
        || original.subtract(d.discount()).compareTo(d.payable()) != 0)
      throw new IllegalStateException("Invalid benefit quote");
    jdbc.update(
        "INSERT INTO order_coupon(order_id,coupon_id,original_amount,discount_amount,xid)"
            + " VALUES(?,?,?,?,?)",
        order.getId(),
        couponId,
        original,
        d.discount(),
        RootContext.getXID());
    jdbc.update("UPDATE `order` SET total_amount=? WHERE id=?", d.payable(), order.getId());
    order.setTotalAmount(d.payable());
  }

  public void requireFinal(Long id) {
    var rows = jdbc.queryForList("SELECT xid FROM order_coupon WHERE order_id=?", String.class, id);
    if (!rows.isEmpty() && fence.held(rows.getFirst()))
      throw new BusinessException("订单事务处理中，请稍后刷新查询");
  }

  public void enrich(OrderVO vo) {
    var rows =
        jdbc.queryForList(
            "SELECT coupon_id,original_amount,discount_amount,xid FROM order_coupon WHERE"
                + " order_id=?",
            vo.getId());
    if (rows.isEmpty()) return;
    var p = rows.getFirst();
    vo.setCouponId(((Number) p.get("coupon_id")).longValue());
    vo.setOriginalAmount((java.math.BigDecimal) p.get("original_amount"));
    vo.setDiscountAmount((java.math.BigDecimal) p.get("discount_amount"));
    vo.setTransactionState(fence.held((String) p.get("xid")) ? "PROCESSING" : "CONFIRMED");
  }
}
