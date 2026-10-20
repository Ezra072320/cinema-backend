package edu.czu.cinema.domain.statistic.service;

import edu.czu.cinema.common.api.ResultCode;
import edu.czu.cinema.common.exception.BusinessException;
import edu.czu.cinema.domain.order.enums.OrderStatus;
import edu.czu.cinema.domain.statistic.dto.RevenueReport;
import edu.czu.cinema.domain.statistic.dto.RevenueReport.*;
import edu.czu.cinema.domain.statistic.dto.StatisticVO;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

@Service
@RequiredArgsConstructor
public class RevenueService {
    private final JdbcTemplate jdbc;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String ORDERS = " FROM " + (char)96 + "order" + (char)96 + " o";
    // 状态码统一取自 OrderStatus 枚举(P1-T6); 报表是手写聚合 SQL, 生成的 SQL 仍是内联字面量, 不参数化.
    // 旧版status=3可能来自未付款的取消；缺失实退金额时不能臆造收款和退款。
    private static final String REFUNDED = "(o.status=" + OrderStatus.REFUNDED.getCode()
            + " AND o.refund_amount IS NOT NULL AND o.refund_amount>=0 AND o.refund_amount<=o.total_amount)";
    private static final String PAID = "(o.status IN(" + OrderStatus.PAID.getCode() + ","
            + OrderStatus.LEGACY_COMPLETED.getCode() + ") OR " + REFUNDED + ")";
    private static final String UNKNOWN = "(o.status=" + OrderStatus.REFUNDED.getCode() + " AND NOT " + REFUNDED + ")";
    private static final String GROSS = "COALESCE(SUM(CASE WHEN " + PAID + " THEN o.total_amount ELSE 0 END),0)";
    private static final String REFUND = "COALESCE(SUM(CASE WHEN " + REFUNDED + " THEN o.refund_amount ELSE 0 END),0)";
    private static final String AGG = GROSS + " AS gross," + REFUND + " AS refunded,COUNT(*) AS total,"
            + count(PAID) + " AS paid," + count(REFUNDED) + " AS refunds,"
            + count("o.status=" + OrderStatus.PENDING.getCode()) + " AS pending,"
            + count("o.status=" + OrderStatus.CLOSED.getCode()) + " AS closed,"
            + count(UNKNOWN) + " AS unknown_count,"
            + "COALESCE(SUM(CASE WHEN " + UNKNOWN + " THEN o.total_amount ELSE 0 END),0) AS unknown_amount";
    private static String count(String condition) {
        return "COALESCE(SUM(CASE WHEN " + condition + " THEN 1 ELSE 0 END),0)";
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RevenueReport report(LocalDate start, LocalDate end, String granularity) {
        LocalDate today = LocalDate.now(ZONE);
        if (end == null) end = today;
        if (start == null) start = end.minusDays(29);
        if (start.isAfter(end) || end.isAfter(today) || start.isBefore(LocalDate.of(2000,1,1))
                || ChronoUnit.DAYS.between(start,end) >= 366)
            throw new BusinessException(ResultCode.BAD_REQUEST, "请选择2000年起、不晚于今天且不超过366天的有效日期区间");
        if (granularity == null || !Set.of("day","week","month").contains(granularity))
            throw new BusinessException(ResultCode.BAD_REQUEST, "统计粒度仅支持day、week或month");
        long days = ChronoUnit.DAYS.between(start,end) + 1;
        LocalDate prevStart = start.minusDays(days), prevEnd = start.minusDays(1);
        var daily = daily(start,end);
        Metrics summary = sum(daily.stream().map(PeriodRow::metrics).toList());
        Metrics previous = aggregate(prevStart,prevEnd);
        return new RevenueReport(start,end,granularity,LocalDateTime.now(ZONE),summary,previous,prevStart,prevEnd,
                buckets(daily,granularity),daily,rank(start,end,true,summary.netRevenue()),
                rank(start,end,false,summary.netRevenue()),distribution(summary));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    @org.springframework.cache.annotation.Cacheable(cacheNames = "statOverview", key = "'overview'")
    public StatisticVO overview() {
        LocalDate today = LocalDate.now(ZONE);
        Metrics all = aggregate(null,null), current = aggregate(today,today);
        var trend = daily(today.minusDays(6),today).stream()
                .map(p -> new StatisticVO.Trend(p.label(),p.metrics().netRevenue(),p.metrics().totalOrders())).toList();
        var top = rank(null,null,true,all.netRevenue()).stream().limit(5)
                .map(r -> new StatisticVO.Movie(r.id(),r.name(),r.netRevenue())).toList();
        var types = jdbc.query("SELECT t.name,COUNT(DISTINCT mt.movie_id) AS n FROM movie_to_type mt"
                        + " JOIN " + (char)96 + "type" + (char)96 + " t ON t.id=mt.type_id JOIN movie m ON m.id=mt.movie_id"
                        + " GROUP BY t.id,t.name ORDER BY n DESC,t.id",
                (rs,row) -> new Distribution(rs.getString("name"),rs.getLong("n")));
        return new StatisticVO(total("user"),all.totalOrders(),all.netRevenue(),total("schedule"),total("movie"),
                current.netRevenue(),current.totalOrders(),trend,top,types);
    }
    private long total(String table) {
        // table仅由上面的固定常量调用。
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM " + (char)96 + table + (char)96,Long.class);
        return n == null ? 0 : n;
    }
    private Metrics aggregate(LocalDate start, LocalDate end) {
        return jdbc.queryForObject("SELECT " + AGG + ORDERS + where(start),(rs,row) -> read(rs),args(start,end));
    }
    private List<PeriodRow> daily(LocalDate start, LocalDate end) {
        Map<LocalDate,Metrics> actual = new HashMap<>();
        jdbc.query("SELECT CAST(o.create_time AS DATE) AS stat_date," + AGG + ORDERS + where(start)
                        + " GROUP BY CAST(o.create_time AS DATE)",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> actual.put(rs.getDate("stat_date").toLocalDate(),read(rs)),
                args(start,end));
        List<PeriodRow> result = new ArrayList<>();
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1))
            result.add(new PeriodRow(day.toString(),day,day,actual.getOrDefault(day,zero())));
        return result;
    }
    private List<RankRow> rank(LocalDate start, LocalDate end, boolean movie, BigDecimal total) {
        String id = movie ? "s.movie_id" : "s.hall_id", name = movie ? "m.title" : "h.name";
        String join = movie ? " LEFT JOIN movie m ON m.id=s.movie_id" : " LEFT JOIN hall h ON h.id=s.hall_id";
        return jdbc.query("SELECT " + id + " AS dimension_id," + name + " AS dimension_name,"
                        + GROSS + " AS gross," + REFUND + " AS refunded," + count(PAID) + " AS paid"
                        + ORDERS + " LEFT JOIN schedule s ON s.id=o.schedule_id" + join
                        + where(start) + (start == null ? " WHERE " : " AND ") + PAID
                        + " GROUP BY " + id + "," + name
                        + " ORDER BY (" + GROSS + "-" + REFUND + ") DESC,paid DESC,dimension_id LIMIT 10",
                (rs,row) -> {
                    long value = rs.getLong("dimension_id"); Long dim = rs.wasNull() ? null : value;
                    String title = rs.getString("dimension_name");
                    if (title == null || title.isBlank()) title = (movie ? "未知或已移除影片" : "未知或已移除影厅") + (dim == null ? "" : " #" + dim);
                    BigDecimal gross = money(rs.getBigDecimal("gross")), refund = money(rs.getBigDecimal("refunded"));
                    BigDecimal net = gross.subtract(refund);
                    return new RankRow(dim,title,rs.getLong("paid"),gross,refund,net,percent(net,total));
                },args(start,end));
    }
    private static String where(LocalDate start) {
        return start == null ? "" : " WHERE o.create_time>=? AND o.create_time<?";
    }
    private static Object[] args(LocalDate start, LocalDate end) {
        return start == null ? new Object[0] : new Object[]{start.atStartOfDay(),end.plusDays(1).atStartOfDay()};
    }
    private static Metrics read(ResultSet rs) throws SQLException {
        return metrics(rs.getBigDecimal("gross"),rs.getBigDecimal("refunded"),rs.getLong("total"),rs.getLong("paid"),
                rs.getLong("refunds"),rs.getLong("pending"),rs.getLong("closed"),rs.getLong("unknown_count"),rs.getBigDecimal("unknown_amount"));
    }
    private static Metrics metrics(BigDecimal gross, BigDecimal refund, long total, long paid, long refunds,
                                   long pending, long closed, long unknown, BigDecimal unknownAmount) {
        gross = money(gross); refund = money(refund);
        return new Metrics(gross,refund,gross.subtract(refund),total,paid,refunds,pending,closed,unknown,money(unknownAmount),
                paid == 0 ? money(BigDecimal.ZERO) : gross.divide(BigDecimal.valueOf(paid),2,RoundingMode.HALF_UP),
                percent(BigDecimal.valueOf(refunds),BigDecimal.valueOf(paid)));
    }
    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2,RoundingMode.HALF_UP);
    }
    private static BigDecimal percent(BigDecimal value, BigDecimal total) {
        return total.signum() <= 0 ? money(BigDecimal.ZERO) : value.multiply(BigDecimal.valueOf(100)).divide(total,2,RoundingMode.HALF_UP);
    }
    private static Metrics zero() { return metrics(BigDecimal.ZERO,BigDecimal.ZERO,0,0,0,0,0,0,BigDecimal.ZERO); }
    private static Metrics sum(List<Metrics> values) {
        BigDecimal gross=BigDecimal.ZERO,refund=BigDecimal.ZERO,unknownAmount=BigDecimal.ZERO;
        long total=0,paid=0,refunds=0,pending=0,closed=0,unknown=0;
        for (Metrics m : values) {
            gross=gross.add(m.grossRevenue()); refund=refund.add(m.refundAmount()); unknownAmount=unknownAmount.add(m.unknownRefundOrderAmount());
            total+=m.totalOrders(); paid+=m.paidOrders(); refunds+=m.refundOrders(); pending+=m.pendingOrders(); closed+=m.closedOrders(); unknown+=m.unknownRefundOrders();
        }
        return metrics(gross,refund,total,paid,refunds,pending,closed,unknown,unknownAmount);
    }
    private static List<PeriodRow> buckets(List<PeriodRow> daily, String grain) {
        if ("day".equals(grain)) return daily;
        Map<LocalDate,List<PeriodRow>> groups = new LinkedHashMap<>();
        for (PeriodRow p : daily) {
            LocalDate key = "week".equals(grain) ? p.startDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    : p.startDate().withDayOfMonth(1);
            groups.computeIfAbsent(key,k -> new ArrayList<>()).add(p);
        }
        return groups.values().stream().map(rows -> {
            LocalDate a=rows.getFirst().startDate(),b=rows.getLast().endDate();
            return new PeriodRow(a + " ~ " + b,a,b,sum(rows.stream().map(PeriodRow::metrics).toList()));
        }).toList();
    }
    private static List<Distribution> distribution(Metrics m) {
        List<Distribution> rows = new ArrayList<>(List.of(new Distribution("已支付 / 已完成",m.paidOrders()-m.refundOrders()),
                new Distribution("已退款",m.refundOrders()),new Distribution("待支付",m.pendingOrders()),
                new Distribution("已关闭",m.closedOrders()),new Distribution("旧退款待核实",m.unknownRefundOrders())));
        long other = m.totalOrders()-m.paidOrders()-m.pendingOrders()-m.closedOrders()-m.unknownRefundOrders();
        if (other > 0) rows.add(new Distribution("其他状态",other));
        return rows;
    }
}
