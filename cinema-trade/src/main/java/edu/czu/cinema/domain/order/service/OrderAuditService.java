package edu.czu.cinema.domain.order.service;

import edu.czu.cinema.domain.order.entity.OrderAction;
import edu.czu.cinema.domain.order.mapper.OrderActionMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.apache.seata.core.context.RootContext;

/** 订单资金操作审计：与业务同事务落一条 {@link OrderAction}，并打 info 日志。 失败不阻断业务（审计表写入异常仅告警），但正常路径必须留痕。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderAuditService {

  private final OrderActionMapper orderActionMapper;
  private final ObjectProvider<OrderEventWriter> eventWriters;

  public void record(
      Long orderId,
      String orderNumber,
      String action,
      Integer fromStatus,
      Integer toStatus,
      BigDecimal amount,
      String operatorType,
      Long operatorId,
      String reason) {
    try {
      OrderAction a = new OrderAction();
      a.setOrderId(orderId);
      a.setOrderNumber(orderNumber);
      a.setAction(action);
      a.setFromStatus(fromStatus);
      a.setToStatus(toStatus);
      a.setAmount(amount);
      a.setOperatorType(operatorType);
      a.setOperatorId(operatorId);
      a.setReason(reason);
      a.setCreatedAt(LocalDateTime.now());
      orderActionMapper.insert(a);
      // The row is staged inside a transaction; a later global rollback is still possible.
      // Free-text reasons and actor details remain in the protected audit table.
      log.atInfo().addKeyValue("event", "order.audit.staged")
          .addKeyValue("order_id", orderId).addKeyValue("xid", RootContext.getXID())
          .log("Order audit staged inside transaction orderNumber={} action={} status={}->{}", orderNumber, action, fromStatus, toStatus);
    } catch (Exception e) {
      log.error("订单审计落库失败 orderId={} action={}", orderId, action, e);
    }
    // Reliable events are outside the best-effort audit catch: an outbox failure rolls back the
    // business transaction.
    var writer = eventWriters.getIfAvailable();
    if (writer != null) writer.record(orderId, action, toStatus, amount);
  }
}
