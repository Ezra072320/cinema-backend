package edu.czu.cinema.domain.order.service;

import edu.czu.cinema.domain.order.entity.OrderAction;
import edu.czu.cinema.domain.order.event.OrderEvent;
import edu.czu.cinema.domain.order.mapper.OrderMapper;
import edu.czu.cinema.messaging.EventOutbox;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The order change and both notifications/timeouts commit in the same local transaction. */
@Component
public class OrderEventWriter {
  private final OrderMapper orders;
  private final EventOutbox outbox;

  public OrderEventWriter(OrderMapper orders, EventOutbox outbox) {
    this.orders = orders;
    this.outbox = outbox;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void record(Long orderId, String action, Integer status, BigDecimal amount) {
    var order = orders.selectById(orderId);
    if (order == null) throw new IllegalStateException("Order event owner missing");
    if (!java.util.Objects.equals(status, order.getStatus()))
      throw new IllegalStateException("Order event state does not match the business transaction");
    OrderEvent.Kind kind =
        switch (action) {
          case OrderAction.ACTION_CREATE -> OrderEvent.Kind.CREATED;
          case OrderAction.ACTION_PAY -> OrderEvent.Kind.PAID;
          case OrderAction.ACTION_REFUND -> OrderEvent.Kind.REFUNDED;
          case OrderAction.ACTION_CLOSE -> OrderEvent.Kind.CLOSED;
          case OrderAction.ACTION_CANCEL_REFUND -> OrderEvent.Kind.CANCEL_REFUNDED;
          default -> throw new IllegalArgumentException("Unknown order action");
        };
    Instant deadline =
        order.getCreateTime() == null
            ? null
            : order
                .getCreateTime()
                .plusMinutes(OrderService.PAY_TIMEOUT_MINUTES)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
    String number =
        order.getNumber() == null || order.getNumber().isBlank()
            ? orderId.toString()
            : order.getNumber();
    var event =
        new OrderEvent(
            UUID.randomUUID().toString(),
            1,
            kind,
            orderId,
            order.getUserId(),
            number,
            status,
            amount,
            Instant.now(),
            deadline,
            MDC.get("traceId"));
    outbox.append(event.eventId(), "cinema-order-events", kind.name(), event, null);
    if (kind == OrderEvent.Kind.CREATED) {
      if (deadline == null) throw new IllegalStateException("New order payment deadline missing");
      var timeout =
          new OrderEvent(
              UUID.randomUUID().toString(),
              1,
              OrderEvent.Kind.PAYMENT_TIMEOUT,
              orderId,
              order.getUserId(),
              number,
              status,
              null,
              event.occurredAt(),
              deadline,
              event.traceId());
      outbox.append(
          timeout.eventId(),
          "cinema-order-timeouts",
          timeout.kind().name(),
          timeout,
          deadline.toEpochMilli());
    }
  }
}
