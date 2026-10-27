package edu.czu.cinema.benefit;

import static org.assertj.core.api.Assertions.*;

import edu.czu.cinema.domain.benefit.CouponContracts.*;
import edu.czu.cinema.domain.order.event.OrderEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.apache.seata.core.context.RootContext;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class CouponServiceTest {
  JdbcTemplate jdbc;
  CouponService coupons;
  TransactionTemplate tx;
  Long template;
  Long coupon;

  @BeforeEach
  void setup() {
    var ds =
        new DriverManagerDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    // Exercise the real business schema, apart from MySQL-only undo_log syntax.
    String ddl;
    try {
      ddl =
          new String(
              new ClassPathResource("db/migration/V1__coupons.sql").getInputStream().readAllBytes(),
              java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    jdbc = new JdbcTemplate(ds);
    ddl = ddl.replaceAll("(?m)^--.*$", "");
    for (String sql :
        ddl.substring(0, ddl.indexOf("CREATE TABLE IF NOT EXISTS `undo_log`")).split(";"))
      if (!sql.isBlank()) jdbc.execute(sql);
    tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    coupons = new CouponService(jdbc);
    template =
        tx.execute(
            s ->
                coupons.create(
                    new TemplateInput(
                        "观影礼", new BigDecimal("10.00"), new BigDecimal("20.00"), 1, 7)));
    coupon = tx.execute(s -> coupons.claim(7L, template));
  }

  @AfterEach
  void clear() {
    RootContext.unbind();
  }

  @Test
  void quoteAcceptsConnectorJDatetimeAsLocalDateTime() {
    var mysql = org.mockito.Mockito.mock(JdbcTemplate.class);
    org.mockito.Mockito.when(mysql.queryForList(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq(coupon),org.mockito.ArgumentMatchers.eq(7L)))
        .thenReturn(java.util.List.of(new java.util.HashMap<>(java.util.Map.of(
            "status","AVAILABLE","expires_at",java.time.LocalDateTime.now().plusDays(1),
            "minimum",new BigDecimal("20.00"),"discount",new BigDecimal("10.00")))));
    assertThat(new CouponService(mysql).quote(request()).payable()).isEqualByComparingTo("40.00");
  }

  Reserve request() {
    return new Reserve(7L, coupon, 100L, new BigDecimal("50.00"));
  }

  void reserve() {
    RootContext.bind("seata:8091:1234");
    tx.execute(s -> coupons.reserve(request()));
    RootContext.unbind();
  }

  OrderEvent event(OrderEvent.Kind kind, int status) {
    return new OrderEvent(
        UUID.randomUUID().toString(),
        1,
        kind,
        100L,
        7L,
        "order100",
        status,
        BigDecimal.TEN,
        Instant.now(),
        null,
        null);
  }

  String status() {
    return jdbc.queryForObject("SELECT status FROM user_coupon WHERE id=?", String.class, coupon);
  }

  @Test
  void duplicateClaimPreservesStockAndOtherUsersCannotExceedIt() {
    Long claimed = tx.execute(s -> coupons.claim(7L, template));
    assertThat(claimed).isEqualTo(coupon);
    assertThat(jdbc.queryForObject("SELECT remaining FROM coupon_template", Integer.class))
        .isZero();
    assertThatThrownBy(() -> tx.execute(s -> coupons.claim(8L, template)))
        .hasMessageContaining("领完");
  }

  @Test
  void reserveRequiresGlobalContextAndChecksOwnerThresholdAndExpiry() {
    assertThatThrownBy(() -> tx.execute(s -> coupons.reserve(request())))
        .hasMessageContaining("全局事务");
    assertThatThrownBy(() -> coupons.quote(new Reserve(8L, coupon, 100L, new BigDecimal("50.00"))))
        .hasMessageContaining("不属于");
    assertThatThrownBy(() -> coupons.quote(new Reserve(7L, coupon, 100L, new BigDecimal("19.99"))))
        .hasMessageContaining("门槛");
    jdbc.update("UPDATE user_coupon SET expires_at=DATEADD('DAY',-1,CURRENT_TIMESTAMP)");
    assertThatThrownBy(() -> coupons.quote(request())).hasMessageContaining("过期");
  }

  @Test
  void reserveIsIdempotentButCannotFreezeSameCouponForAnotherOrder() {
    reserve();
    RootContext.bind("seata:8091:1234");
    assertThat(tx.execute(s -> coupons.reserve(request())).payable()).isEqualByComparingTo("40.00");
    assertThatThrownBy(
            () ->
                tx.execute(
                    s -> coupons.reserve(new Reserve(7L, coupon, 101L, new BigDecimal("50.00")))))
        .hasMessageContaining("占用");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_reservation", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void branchFailureRollsBackCouponAndReservationTogether() {
    RootContext.bind("seata:8091:1234");
    assertThatThrownBy(
            () ->
                tx.execute(
                    s -> {
                      coupons.reserve(request());
                      throw new IllegalStateException("branch failed");
                    }))
        .hasMessage("branch failed");
    assertThat(status()).isEqualTo("AVAILABLE");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_reservation", Integer.class))
        .isZero();
  }

  @Test
  void cancellationReleasesOnceAndOldFactsCannotReleaseANewReservation() {
    reserve();
    var closed = event(OrderEvent.Kind.CLOSED, 4);
    tx.executeWithoutResult(s -> coupons.receive(closed, "hash"));
    tx.executeWithoutResult(s -> coupons.receive(closed, "hash"));
    assertThat(status()).isEqualTo("AVAILABLE");
    RootContext.bind("seata:8091:5678");
    tx.execute(s -> coupons.reserve(new Reserve(7L, coupon, 101L, new BigDecimal("50.00"))));
    RootContext.unbind();
    tx.executeWithoutResult(s -> coupons.receive(event(OrderEvent.Kind.CLOSED, 4), "later"));
    assertThat(status()).isEqualTo("FROZEN");
    assertThat(jdbc.queryForObject("SELECT order_id FROM user_coupon", Long.class)).isEqualTo(101L);
  }

  @Test
  void refundArrivingBeforePaymentStillConsumesAndNeverReturnsCoupon() {
    reserve();
    tx.executeWithoutResult(s -> coupons.receive(event(OrderEvent.Kind.REFUNDED, 3), "refund"));
    tx.executeWithoutResult(s -> coupons.receive(event(OrderEvent.Kind.PAID, 2), "pay"));
    assertThat(status()).isEqualTo("USED");
  }

  @Test
  void changedEventIdentityAndWrongOwnersDoNotMutateOrAcknowledge() {
    reserve();
    var paid = event(OrderEvent.Kind.PAID, 2);
    tx.executeWithoutResult(s -> coupons.receive(paid, "original"));
    assertThatThrownBy(() -> tx.executeWithoutResult(s -> coupons.receive(paid, "changed")))
        .hasMessageContaining("Changed event");
    var wrong =
        new OrderEvent(
            UUID.randomUUID().toString(),
            1,
            OrderEvent.Kind.CLOSED,
            100L,
            8L,
            "order100",
            4,
            null,
            Instant.now(),
            null,
            null);
    assertThatThrownBy(() -> tx.executeWithoutResult(s -> coupons.receive(wrong, "wrong")))
        .hasMessageContaining("owner mismatch");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consumed_order_event", Integer.class))
        .isEqualTo(1);
  }
}
