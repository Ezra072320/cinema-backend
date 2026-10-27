package edu.czu.cinema.transactions;

import edu.czu.cinema.messaging.OutboxAppendFence;
import java.util.*;
import org.apache.seata.core.context.RootContext;
import org.apache.seata.core.model.GlobalStatus;
import org.apache.seata.tm.api.GlobalTransactionContext;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** HELD rows are never leased or sent. A disappeared TC record alone is NOT proof of commit. */
@Component
public class GlobalOutboxFence implements OutboxAppendFence {
  private static final Logger log = LoggerFactory.getLogger(GlobalOutboxFence.class);
  private final JdbcTemplate jdbc;
  private final boolean enabled;

  public GlobalOutboxFence(JdbcTemplate jdbc, @Value("${seata.enabled:false}") boolean enabled) {
    this.jdbc = jdbc;
    this.enabled = enabled;
  }

  public void capture(String id) {
    String xid = RootContext.getXID();
    if (xid == null) return;
    jdbc.update("UPDATE event_outbox SET status='HELD' WHERE event_id=?", id);
    jdbc.update("INSERT INTO event_outbox_fence(event_id,xid) VALUES(?,?)", id, xid);
  }

  public boolean held(String xid) {
    return jdbc.queryForObject(
            "SELECT COUNT(*) FROM event_outbox e JOIN event_outbox_fence f ON f.event_id=e.event_id"
                + " WHERE f.xid=? AND e.status='HELD'",
            Long.class,
            xid)
        > 0;
  }

  @Scheduled(fixedDelay = 2000, initialDelay = 5000)
  public void reconcile() {
    if (!enabled) return;
    for (String xid :
        jdbc.queryForList(
            "SELECT DISTINCT f.xid FROM event_outbox_fence f JOIN event_outbox e ON"
                + " e.event_id=f.event_id WHERE e.status='HELD' LIMIT 20",
            String.class)) {
      try {
        GlobalStatus status = GlobalTransactionContext.reload(xid).getStatus();
        // TC removes a final session. Waiting for local undo cleanup also excludes rollback in
        // progress.
        if ((status == GlobalStatus.Committed || status == GlobalStatus.Finished)
            && jdbc.queryForObject("SELECT COUNT(*) FROM undo_log WHERE xid=?", Long.class, xid)
                == 0)
          jdbc.update(
              "UPDATE event_outbox SET status='PENDING' WHERE status='HELD' AND event_id IN (SELECT"
                  + " event_id FROM event_outbox_fence WHERE xid=?)",
              xid);
      } catch (Exception failure) {
        log.debug(
            "Global decision pending xid={} failure={}", xid, failure.getClass().getSimpleName());
      }
    }
  }
}
