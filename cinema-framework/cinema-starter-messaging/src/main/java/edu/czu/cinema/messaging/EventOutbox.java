package edu.czu.cinema.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Appends to the caller's database in the business transaction; never sends over the network. */
@Component
public class EventOutbox {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public EventOutbox(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @org.springframework.beans.factory.annotation.Autowired(required=false)
  private OutboxAppendFence fence;

  @Transactional(propagation = Propagation.MANDATORY)
  public void append(
      String eventId, String topic, String tag, Object event, Long deliveryTimestamp) {
    if (eventId == null
        || eventId.length() > 64
        || eventId.isBlank()
        || topic == null
        || !topic.matches("cinema-[a-z-]{1,60}")
        || tag == null
        || !tag.matches("[A-Z_]{1,64}"))
      throw new IllegalArgumentException("Invalid event identity");
    try {
      String payload = json.writeValueAsString(event);
      if (payload.length() > 200_000) throw new IllegalArgumentException("Event payload too large");
      jdbc.update(
          "INSERT INTO event_outbox(event_id,topic,event_tag,payload,delivery_timestamp,"
              + "status,attempts,next_attempt_at,created_at) VALUES(?,?,?,?,?,'PENDING',0,?,?)",
          eventId,
          topic,
          tag,
          payload,
          deliveryTimestamp,
          System.currentTimeMillis(),
          System.currentTimeMillis());
      if(fence!=null) fence.capture(eventId);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Invalid event payload", e);
    }
  }
}
