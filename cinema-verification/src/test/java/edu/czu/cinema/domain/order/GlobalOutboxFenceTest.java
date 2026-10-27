package edu.czu.cinema.domain.order;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import edu.czu.cinema.messaging.OutboxStore;
import edu.czu.cinema.transactions.GlobalOutboxFence;
import java.util.UUID;
import org.apache.seata.core.context.RootContext;
import org.apache.seata.core.model.GlobalStatus;
import org.apache.seata.tm.api.GlobalTransaction;
import org.apache.seata.tm.api.GlobalTransactionContext;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class GlobalOutboxFenceTest {
  JdbcTemplate jdbc;
  GlobalOutboxFence fence;
  OutboxStore store;
  TransactionTemplate tx;

  @BeforeEach
  void setup() {
    var ds =
        new DriverManagerDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    jdbc = new JdbcTemplate(ds);
    jdbc.execute(
        "CREATE TABLE event_outbox(event_id VARCHAR(64) PRIMARY KEY,topic VARCHAR(80),event_tag"
            + " VARCHAR(64),payload CLOB,delivery_timestamp BIGINT,status VARCHAR(16),attempts"
            + " INT,next_attempt_at BIGINT,created_at BIGINT,lease_token VARCHAR(36),lease_until"
            + " BIGINT)");
    jdbc.execute(
        "CREATE TABLE event_outbox_fence(event_id VARCHAR(64) PRIMARY KEY,xid VARCHAR(128))");
    jdbc.execute("CREATE TABLE undo_log(xid VARCHAR(128))");
    tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    fence = new GlobalOutboxFence(jdbc, true);
    store = new OutboxStore(jdbc);
    RootContext.bind("seata:8091:42");
    tx.executeWithoutResult(
        s -> {
          jdbc.update(
              "INSERT INTO"
                  + " event_outbox(event_id,topic,event_tag,payload,status,attempts,next_attempt_at,created_at)"
                  + " VALUES('event','cinema-order-events','CREATED','{}','PENDING',0,0,0)");
          fence.capture("event");
        });
    RootContext.unbind();
  }

  @AfterEach
  void clear() {
    RootContext.unbind();
  }

  String status() {
    return jdbc.queryForObject("SELECT status FROM event_outbox", String.class);
  }

  @Test
  void phaseOneAndCoordinatorFailureNeverLeaseOrPublish() throws Exception {
    var global = mock(GlobalTransaction.class);
    try (var contexts = mockStatic(GlobalTransactionContext.class)) {
      contexts.when(() -> GlobalTransactionContext.reload("seata:8091:42")).thenReturn(global);
      when(global.getStatus()).thenReturn(GlobalStatus.Begin);
      fence.reconcile();
      assertThat(status()).isEqualTo("HELD");
      java.util.List<OutboxStore.Delivery> claimed =
          tx.execute(s -> store.claim(System.currentTimeMillis()));
      assertThat(claimed).isEmpty();
      when(global.getStatus()).thenThrow(new IllegalStateException("TC unavailable"));
      fence.reconcile();
      assertThat(status()).isEqualTo("HELD");
    }
  }

  @Test
  void missingGlobalRecordNeedsLocalUndoCleanupBeforePublication() throws Exception {
    var global = mock(GlobalTransaction.class);
    try (var contexts = mockStatic(GlobalTransactionContext.class)) {
      contexts.when(() -> GlobalTransactionContext.reload("seata:8091:42")).thenReturn(global);
      when(global.getStatus()).thenReturn(GlobalStatus.Finished);
      jdbc.update("INSERT INTO undo_log VALUES('seata:8091:42')");
      fence.reconcile();
      assertThat(status()).isEqualTo("HELD");
      jdbc.update("DELETE FROM undo_log");
      fence.reconcile();
      assertThat(status()).isEqualTo("PENDING");
      java.util.List<OutboxStore.Delivery> claimed =
          tx.execute(s -> store.claim(System.currentTimeMillis()));
      assertThat(claimed).hasSize(1);
    }
  }

  @Test
  void rollbackAndRetryingRollbackAreNotSuccess() throws Exception {
    var global = mock(GlobalTransaction.class);
    try (var contexts = mockStatic(GlobalTransactionContext.class)) {
      contexts.when(() -> GlobalTransactionContext.reload("seata:8091:42")).thenReturn(global);
      for (var state :
          java.util.List.of(
              GlobalStatus.RollbackRetrying,
              GlobalStatus.Rollbacked,
              GlobalStatus.TimeoutRollbacked)) {
        when(global.getStatus()).thenReturn(state);
        fence.reconcile();
        assertThat(status()).isEqualTo("HELD");
      }
    }
  }
}
