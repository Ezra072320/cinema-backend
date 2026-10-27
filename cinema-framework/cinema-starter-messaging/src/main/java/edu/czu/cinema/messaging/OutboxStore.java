package edu.czu.cinema.messaging;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Durable lease fences an expired publisher. A lost send acknowledgement can cause redelivery. */
@Component
public class OutboxStore {
  public record Delivery(
      String eventId,
      String topic,
      String tag,
      String payload,
      Long deliveryTimestamp,
      int attempts,
      String leaseToken) {}

  private final JdbcTemplate jdbc;

  public OutboxStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional
  public List<Delivery> claim(long now) {
    var candidates =
        jdbc.query(
            "SELECT event_id,topic,event_tag,payload,delivery_timestamp,attempts FROM event_outbox"
                + " WHERE (status='PENDING' AND next_attempt_at<=?) OR (status='SENDING' AND"
                + " lease_until<?) ORDER BY created_at LIMIT 20 FOR UPDATE SKIP LOCKED",
            (row, index) ->
                new Delivery(
                    row.getString("event_id"),
                    row.getString("topic"),
                    row.getString("event_tag"),
                    row.getString("payload"),
                    row.getObject("delivery_timestamp", Long.class),
                    row.getInt("attempts"),
                    UUID.randomUUID().toString()),
            now,
            now);
    for (var row : candidates)
      jdbc.update(
          "UPDATE event_outbox SET status='SENDING',lease_token=?,lease_until=? WHERE event_id=?",
          row.leaseToken(),
          now + 180_000,
          row.eventId());
    return candidates;
  }

  public boolean sent(Delivery row) {
    return jdbc.update(
            "UPDATE event_outbox SET status='SENT',sent_at=?,lease_token=NULL,lease_until=NULL "
                + "WHERE event_id=? AND status='SENDING' AND lease_token=?",
            System.currentTimeMillis(),
            row.eventId(),
            row.leaseToken())
        == 1;
  }

  public void retry(Delivery row, String failure) {
    long delay = Math.min(300_000L, 1000L << Math.min(8, row.attempts()));
    jdbc.update(
        "UPDATE event_outbox SET"
            + " status='PENDING',attempts=attempts+1,next_attempt_at=?,last_error=?,lease_token=NULL,lease_until=NULL"
            + " WHERE event_id=? AND status='SENDING' AND lease_token=?",
        System.currentTimeMillis() + delay,
        failure,
        row.eventId(),
        row.leaseToken());
  }
}
