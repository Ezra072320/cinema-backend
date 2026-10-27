package edu.czu.cinema.domain.order.event;

import java.math.BigDecimal;
import java.time.Instant;

/** Versioned order fact, with no authentication token or private profile data. */
public record OrderEvent(
    String eventId,
    int schemaVersion,
    Kind kind,
    Long orderId,
    Long userId,
    String orderNumber,
    Integer status,
    BigDecimal amount,
    Instant occurredAt,
    Instant paymentDeadline,
    String traceId) {
  public enum Kind {
    CREATED,
    PAID,
    REFUNDED,
    CLOSED,
    CANCEL_REFUNDED,
    PAYMENT_TIMEOUT
  }

  public OrderEvent {
    if (eventId == null
        || !eventId.matches("[a-f0-9-]{36}")
        || schemaVersion != 1
        || kind == null
        || orderId == null
        || orderId <= 0
        || userId == null
        || userId <= 0
        || orderNumber == null
        || orderNumber.isBlank()
        || orderNumber.length() > 64
        || occurredAt == null
        || status == null) throw new IllegalArgumentException("Invalid order event");
  }
}
