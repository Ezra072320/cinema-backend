package edu.czu.cinema.messaging;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

@SpringJUnitConfig(EventOutboxTest.Config.class)
class EventOutboxTest {
  @Configuration
  @EnableTransactionManagement
  static class Config {
    @Bean
    DriverManagerDataSource dataSource() {
      return new DriverManagerDataSource(
          "jdbc:h2:mem:outbox;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }

    @Bean
    JdbcTemplate jdbc(DriverManagerDataSource ds) {
      return new JdbcTemplate(ds);
    }

    @Bean
    PlatformTransactionManager tx(DriverManagerDataSource ds) {
      return new DataSourceTransactionManager(ds);
    }

    @Bean
    EventOutbox outbox(JdbcTemplate jdbc) {
      return new EventOutbox(jdbc, new ObjectMapper());
    }

    @Bean
    OutboxStore store(JdbcTemplate jdbc) {
      return new OutboxStore(jdbc);
    }
  }

  @Autowired EventOutbox outbox;
  @Autowired OutboxStore store;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager tx;

  @BeforeEach
  void schema() {
    jdbc.execute("DROP ALL OBJECTS");
    jdbc.execute("CREATE TABLE business_change(id INT PRIMARY KEY)");
    jdbc.execute(
        "CREATE TABLE event_outbox(event_id VARCHAR(64) PRIMARY KEY,topic VARCHAR(80),event_tag"
            + " VARCHAR(64),payload LONGTEXT,delivery_timestamp BIGINT,status VARCHAR(16),attempts"
            + " INT,next_attempt_at BIGINT,lease_token VARCHAR(36),lease_until BIGINT,last_error"
            + " VARCHAR(128),created_at BIGINT,sent_at BIGINT)");
  }

  private String append() {
    String id = UUID.randomUUID().toString();
    new TransactionTemplate(tx)
        .executeWithoutResult(
            status ->
                outbox.append(id, "cinema-order-events", "PAID", Map.of("orderId", 12), null));
    return id;
  }

  @Test
  void requiresAnActualBusinessTransaction() {
    assertThatThrownBy(
            () ->
                outbox.append(
                    UUID.randomUUID().toString(), "cinema-order-events", "PAID", Map.of(), null))
        .isInstanceOf(IllegalTransactionStateException.class);
  }

  @Test
  void failedBusinessChangeLeavesNoDeliverableEvent() {
    assertThatThrownBy(
            () ->
                new TransactionTemplate(tx)
                    .executeWithoutResult(
                        status -> {
                          jdbc.update("INSERT INTO business_change VALUES(1)");
                          outbox.append(
                              UUID.randomUUID().toString(),
                              "cinema-order-events",
                              "CREATED",
                              Map.of("orderId", 12),
                              null);
                          throw new IllegalStateException("simulated failure");
                        }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM business_change", Integer.class)).isZero();
    assertThat(store.claim(System.currentTimeMillis())).isEmpty();
  }

  @Test
  void successfulSendIsNotReclaimedAndCanOnlyBeMarkedByItsLease() {
    String id = append();
    var delivery = store.claim(System.currentTimeMillis()).getFirst();
    assertThat(delivery.eventId()).isEqualTo(id);
    assertThat(store.claim(System.currentTimeMillis())).isEmpty();
    assertThat(store.sent(delivery)).isTrue();
    assertThat(store.sent(delivery)).isFalse();
    assertThat(store.claim(System.currentTimeMillis() + 999999)).isEmpty();
  }

  @Test
  void crashedOrUncertainPublisherIsReclaimedWithSameEventIdAndANewLease() {
    String id = append();
    long now = System.currentTimeMillis();
    var old = store.claim(now).getFirst();
    var recovered = store.claim(now + 180001).getFirst();
    assertThat(recovered.eventId()).isEqualTo(id);
    assertThat(recovered.leaseToken()).isNotEqualTo(old.leaseToken());
    assertThat(store.sent(old)).isFalse();
    assertThat(store.sent(recovered)).isTrue();
  }

  @Test
  void unavailableBrokerKeepsTheEventAndBacksOffWithoutLosingItsIdentity() {
    String id = append();
    var delivery = store.claim(System.currentTimeMillis()).getFirst();
    store.retry(delivery, "ClientException");
    assertThat(store.claim(System.currentTimeMillis())).isEmpty();
    var retried = store.claim(System.currentTimeMillis() + 2000).getFirst();
    assertThat(retried.eventId()).isEqualTo(id);
    assertThat(retried.attempts()).isEqualTo(1);
  }
}
