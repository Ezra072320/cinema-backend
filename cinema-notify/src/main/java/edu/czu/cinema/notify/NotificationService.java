package edu.czu.cinema.notify;

import edu.czu.cinema.common.api.PageResult;
import edu.czu.cinema.domain.order.event.OrderEvent;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {
  public record Item(
      Long id,
      Long orderId,
      String title,
      String body,
      String eventKind,
      LocalDateTime createdAt,
      LocalDateTime readAt) {}

  private final JdbcTemplate jdbc;

  public NotificationService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * The dedupe marker and notification are committed together before the broker is acknowledged.
   */
  @Transactional
  public boolean receive(OrderEvent event, String payloadHash) {
    if (event.kind() == OrderEvent.Kind.PAYMENT_TIMEOUT)
      throw new IllegalArgumentException("Wrong notification topic");
    int inserted =
        jdbc.update(
            "INSERT IGNORE INTO consumed_event(event_id,payload_hash) VALUES(?,?)",
            event.eventId(),
            payloadHash);
    if (inserted == 0) {
      String previous =
          jdbc.queryForObject(
              "SELECT payload_hash FROM consumed_event WHERE event_id=?",
              String.class,
              event.eventId());
      if (!payloadHash.equals(previous))
        throw new IllegalArgumentException("Conflicting duplicate event");
      return false;
    }
    String title =
        switch (event.kind()) {
          case CREATED -> "订单待支付";
          case PAID -> "支付成功";
          case REFUNDED, CANCEL_REFUNDED -> "退款已办理";
          case CLOSED -> "订单已关闭";
          default -> throw new IllegalArgumentException("Unsupported event");
        };
    jdbc.update(
        "INSERT INTO notification(event_id,user_id,order_id,event_kind,title,body,created_at)"
            + " VALUES(?,?,?,?,?,?,?)",
        event.eventId(),
        event.userId(),
        event.orderId(),
        event.kind().name(),
        title,
        "订单 " + event.orderNumber() + "：" + title + "。请打开订单查看当前详情。",
        LocalDateTime.ofInstant(event.occurredAt(), ZoneId.of("Asia/Shanghai")));
    return true;
  }

  @Transactional(readOnly = true)
  public PageResult<Item> list(Long userId, int page, int size, boolean unread) {
    int boundedSize = Math.max(1, Math.min(50, size));
    int boundedPage = Math.max(1, Math.min(1000, page));
    String condition = "user_id=?" + (unread ? " AND read_at IS NULL" : "");
    Long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM notification WHERE " + condition, Long.class, userId);
    var items =
        jdbc.query(
            "SELECT * FROM notification WHERE " + condition + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
            (row, index) ->
                new Item(
                    row.getLong("id"),
                    row.getLong("order_id"),
                    row.getString("title"),
                    row.getString("body"),
                    row.getString("event_kind"),
                    row.getObject("created_at", LocalDateTime.class),
                    row.getObject("read_at", LocalDateTime.class)),
            userId,
            boundedSize,
            (boundedPage - 1) * boundedSize);
    return PageResult.of(total == null ? 0 : total, items);
  }

  public long unread(Long userId) {
    Long value =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM notification WHERE user_id=? AND read_at IS NULL",
            Long.class,
            userId);
    return value == null ? 0 : value;
  }

  public void markRead(Long userId, Long id) {
    jdbc.update(
        "UPDATE notification SET read_at=CURRENT_TIMESTAMP(3) WHERE id=? AND user_id=? AND read_at"
            + " IS NULL",
        id,
        userId);
  }

  public void markAllRead(Long userId) {
    jdbc.update(
        "UPDATE notification SET read_at=CURRENT_TIMESTAMP(3) WHERE user_id=? AND read_at IS NULL",
        userId);
  }
}
