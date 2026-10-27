package edu.czu.cinema.notify;

import static org.assertj.core.api.Assertions.*;

import edu.czu.cinema.domain.order.event.OrderEvent;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringJUnitConfig(NotificationServiceTest.Config.class)
class NotificationServiceTest {
  @Test
  void delayedEventsKeepBusinessTimeInsteadOfReplacingNewerResultsAtTheTop() {
    var paid = event(7);
    notifications.receive(paid, "a".repeat(64));
    var created = new OrderEvent(UUID.randomUUID().toString(), 1, OrderEvent.Kind.CREATED,
        12L, 7L, "202610220001", 1, null, Instant.now().minusSeconds(3600), null, "test-trace");
    notifications.receive(created, "b".repeat(64));
    var rows = notifications.list(7L, 1, 20, false).getRows();
    assertThat(rows.getFirst().eventKind()).isEqualTo("PAID");
    assertThat(rows.get(1).createdAt()).isBefore(rows.getFirst().createdAt().minusMinutes(30));
  }
  @Configuration
  @EnableTransactionManagement
  static class Config {
    @Bean
    DriverManagerDataSource ds() {
      return new DriverManagerDataSource(
          "jdbc:h2:mem:notify;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
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
    NotificationService notifications(JdbcTemplate jdbc) {
      return new NotificationService(jdbc);
    }
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired NotificationService notifications;

  @BeforeEach
  void schema() {
    jdbc.execute("DROP ALL OBJECTS");
    jdbc.execute(
        "CREATE TABLE consumed_event(event_id VARCHAR(64) PRIMARY KEY,payload_hash CHAR(64))");
    jdbc.execute(
        "CREATE TABLE notification(id BIGINT AUTO_INCREMENT PRIMARY KEY,event_id VARCHAR(64)"
            + " UNIQUE,user_id BIGINT,order_id BIGINT,event_kind VARCHAR(32),title"
            + " VARCHAR(100),body VARCHAR(500),created_at TIMESTAMP DEFAULT"
            + " CURRENT_TIMESTAMP,read_at TIMESTAMP NULL)");
  }

  private OrderEvent event(long userId) {
    return new OrderEvent(
        UUID.randomUUID().toString(),
        1,
        OrderEvent.Kind.PAID,
        12L,
        userId,
        "202610220001",
        2,
        null,
        Instant.now(),
        null,
        "test-trace");
  }

  @Test
  void duplicateDeliveryProducesOneNotificationAndConflictingPayloadIsRejected() {
    var event = event(7);
    assertThat(notifications.receive(event, "a".repeat(64))).isTrue();
    assertThat(notifications.receive(event, "a".repeat(64))).isFalse();
    assertThatThrownBy(() -> notifications.receive(event, "b".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(notifications.unread(7L)).isEqualTo(1);
  }

  @Test
  void failedNotificationInsertDoesNotLeaveADedupeMarker() {
    jdbc.execute("DROP TABLE notification");
    assertThatThrownBy(() -> notifications.receive(event(7), "a".repeat(64)))
        .isInstanceOf(RuntimeException.class);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consumed_event", Integer.class)).isZero();
  }

  @Test
  void listsCountsAndReadMarkersAreIsolatedByTheAuthenticatedUser() {
    notifications.receive(event(7), "a".repeat(64));
    notifications.receive(event(8), "b".repeat(64));
    var item = notifications.list(7L, 1, 20, false).getRows().getFirst();
    notifications.markRead(8L, item.id());
    assertThat(notifications.unread(7L)).isEqualTo(1);
    notifications.markAllRead(8L);
    assertThat(notifications.unread(8L)).isZero();
    assertThat(notifications.unread(7L)).isEqualTo(1);
    notifications.markRead(7L, item.id());
    assertThat(notifications.list(7L, 1, 20, true).getTotal()).isZero();
    assertThat(notifications.list(7L, 1, 20, false).getTotal()).isEqualTo(1);
  }
}
