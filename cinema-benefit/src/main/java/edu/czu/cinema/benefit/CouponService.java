package edu.czu.cinema.benefit;

import edu.czu.cinema.common.exception.BusinessException;
import edu.czu.cinema.domain.benefit.CouponContracts.*;
import edu.czu.cinema.domain.order.event.OrderEvent;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import org.apache.seata.core.context.RootContext;
import org.apache.seata.spring.annotation.GlobalLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CouponService {
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(CouponService.class);
  private final JdbcTemplate jdbc;

  public CouponService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Template> templates() {
    return jdbc.query(
        "SELECT * FROM coupon_template ORDER BY id DESC LIMIT 100",
        (r, i) ->
            new Template(
                r.getLong("id"),
                r.getString("name"),
                r.getBigDecimal("discount"),
                r.getBigDecimal("minimum"),
                r.getInt("remaining"),
                r.getInt("valid_days")));
  }

  @Transactional
  public Long create(TemplateInput t) {
    if (t.discount().compareTo(t.minimum()) >= 0) throw new BusinessException("优惠金额必须小于使用门槛");
    var key = new GeneratedKeyHolder();
    jdbc.update(
        c -> {
          var p =
              c.prepareStatement(
                  "INSERT INTO coupon_template(name,discount,minimum,remaining,valid_days)"
                      + " VALUES(?,?,?,?,?)",
                  new String[] {"id"});
          p.setString(1, t.name());
          p.setBigDecimal(2, t.discount());
          p.setBigDecimal(3, t.minimum());
          p.setInt(4, t.stock());
          p.setInt(5, t.validDays());
          return p;
        },
        key);
    return Objects.requireNonNull(key.getKey()).longValue();
  }

  @GlobalLock
  @Transactional
  public Long claim(Long uid, Long templateId) {
    var rows = jdbc.queryForList("SELECT * FROM coupon_template WHERE id=? FOR UPDATE", templateId);
    if (rows.isEmpty()) throw new BusinessException("优惠券不存在");
    var existing =
        jdbc.queryForList(
            "SELECT id FROM user_coupon WHERE template_id=? AND user_id=?",
            Long.class,
            templateId,
            uid);
    if (!existing.isEmpty()) return existing.getFirst();
    var t = rows.getFirst();
    if (((Number) t.get("remaining")).intValue() <= 0) throw new BusinessException("优惠券已领完");
    jdbc.update("UPDATE coupon_template SET remaining=remaining-1 WHERE id=?", templateId);
    var key = new GeneratedKeyHolder();
    var expires = LocalDateTime.now().plusDays(((Number) t.get("valid_days")).intValue());
    jdbc.update(
        c -> {
          var p =
              c.prepareStatement(
                  "INSERT INTO user_coupon(template_id,user_id,status,expires_at)"
                      + " VALUES(?,?,'AVAILABLE',?)",
                  new String[] {"id"});
          p.setLong(1, templateId);
          p.setLong(2, uid);
          p.setTimestamp(3, Timestamp.valueOf(expires));
          return p;
        },
        key);
    return Objects.requireNonNull(key.getKey()).longValue();
  }

  public List<Coupon> coupons(Long uid) {
    return jdbc.query(
        "SELECT c.*,t.name,t.discount,t.minimum FROM user_coupon c JOIN coupon_template t ON"
            + " t.id=c.template_id WHERE c.user_id=? ORDER BY c.id DESC LIMIT 200",
        (r, i) ->
            new Coupon(
                r.getLong("id"),
                r.getString("name"),
                r.getBigDecimal("discount"),
                r.getBigDecimal("minimum"),
                "AVAILABLE".equals(r.getString("status"))
                        && r.getTimestamp("expires_at")
                            .toLocalDateTime()
                            .isBefore(LocalDateTime.now())
                    ? "EXPIRED"
                    : r.getString("status"),
                r.getTimestamp("expires_at").toLocalDateTime().toString(),
                r.getObject("order_id", Long.class)),
        uid);
  }

  public Discount quote(Reserve q) {
    var rows =
        jdbc.queryForList(
            "SELECT c.*,t.discount,t.minimum FROM user_coupon c JOIN coupon_template t ON"
                + " t.id=c.template_id WHERE c.id=? AND c.user_id=?",
            q.couponId(),
            q.userId());
    if (rows.isEmpty()) throw new BusinessException("优惠券不属于当前用户");
    return validate(q, rows.getFirst());
  }

  private Discount validate(Reserve q, Map<String, Object> c) {
    if (!"AVAILABLE".equals(c.get("status"))) throw new BusinessException("优惠券已使用或已被订单占用");
    Object expires = c.get("expires_at");
    LocalDateTime expiry = expires instanceof Timestamp timestamp
        ? timestamp.toLocalDateTime() : (LocalDateTime) expires;
    if (!expiry.isAfter(LocalDateTime.now()))
      throw new BusinessException("优惠券已过期");
    if (q.originalAmount().compareTo((BigDecimal) c.get("minimum")) < 0)
      throw new BusinessException("订单未达到优惠券使用门槛");
    var discount = (BigDecimal) c.get("discount");
    return new Discount(q.couponId(), discount, q.originalAmount().subtract(discount));
  }

  @Transactional
  public Discount reserve(Reserve q) {
    if (RootContext.getXID() == null) throw new BusinessException("优惠券订单需要全局事务");
    var prior = jdbc.queryForList("SELECT * FROM coupon_reservation WHERE order_id=?", q.orderId());
    if (!prior.isEmpty()) {
      var p = prior.getFirst();
      if (!Objects.equals(((Number) p.get("user_id")).longValue(), q.userId())
          || !Objects.equals(((Number) p.get("coupon_id")).longValue(), q.couponId())
          || ((BigDecimal) p.get("original_amount")).compareTo(q.originalAmount()) != 0
          || !"FROZEN".equals(p.get("status"))) throw new BusinessException("订单与优惠券预留不匹配");
      var d = (BigDecimal) p.get("discount");
      return new Discount(q.couponId(), d, q.originalAmount().subtract(d));
    }
    // Lock the coupon before checking availability; concurrent checkout cannot freeze it twice.
    var rows =
        jdbc.queryForList(
            "SELECT * FROM user_coupon WHERE id=? AND user_id=? FOR UPDATE",
            q.couponId(),
            q.userId());
    if (rows.isEmpty()) throw new BusinessException("优惠券不属于当前用户");
    var c = rows.getFirst();
    var t =
        jdbc.queryForMap(
            "SELECT discount,minimum FROM coupon_template WHERE id=?", c.get("template_id"));
    c.putAll(t);
    var d = validate(q, c);
    jdbc.update(
        "UPDATE user_coupon SET status='FROZEN',order_id=? WHERE id=?", q.orderId(), q.couponId());
    jdbc.update(
        "INSERT INTO"
            + " coupon_reservation(order_id,coupon_id,user_id,original_amount,discount,status,xid)"
            + " VALUES(?,?,?,?,?,'FROZEN',?)",
        q.orderId(),
        q.couponId(),
        q.userId(),
        q.originalAmount(),
        d.discount(),
        RootContext.getXID());
    LOG.atInfo().addKeyValue("event", "coupon.reservation.staged")
        .addKeyValue("order_id", q.orderId()).addKeyValue("xid", RootContext.getXID())
        .log("Coupon reservation staged inside global transaction");
    return d;
  }

  @GlobalLock
  @Transactional
  public void receive(OrderEvent event, String hash) {
    if (event.schemaVersion() != 1
        || event.eventId() == null
        || event.orderId() == null
        || event.userId() == null) throw new IllegalArgumentException("Invalid order fact");
    var seen =
        jdbc.queryForList(
            "SELECT payload_hash FROM consumed_order_event WHERE event_id=?",
            String.class,
            event.eventId());
    if (!seen.isEmpty()) {
      if (!hash.equals(seen.getFirst()))
        throw new IllegalArgumentException("Changed event identity");
      return;
    }
    var rows =
        jdbc.queryForList(
            "SELECT * FROM coupon_reservation WHERE order_id=? FOR UPDATE", event.orderId());
    if (!rows.isEmpty()) {
      var p = rows.getFirst();
      if (((Number) p.get("user_id")).longValue() != event.userId())
        throw new IllegalArgumentException("Order owner mismatch");
      String next =
          switch (event.kind()) {
            case PAID, REFUNDED, CANCEL_REFUNDED -> "USED";
            case CLOSED -> "RELEASED";
            default -> null;
          };
      if (next != null && "FROZEN".equals(p.get("status"))) {
        jdbc.queryForList("SELECT id FROM user_coupon WHERE id=? FOR UPDATE", p.get("coupon_id"));
        int changed =
            jdbc.update(
                "UPDATE user_coupon SET status=?,order_id=? WHERE id=? AND order_id=? AND"
                    + " status='FROZEN'",
                "USED".equals(next) ? "USED" : "AVAILABLE",
                "USED".equals(next) ? event.orderId() : null,
                p.get("coupon_id"),
                event.orderId());
        if (changed != 1) throw new IllegalStateException("Coupon reservation mismatch");
        jdbc.update(
            "UPDATE coupon_reservation SET status=? WHERE order_id=?", next, event.orderId());
      }
    }
    jdbc.update(
        "INSERT INTO consumed_order_event(event_id,payload_hash) VALUES(?,?)",
        event.eventId(),
        hash);
  }
}
