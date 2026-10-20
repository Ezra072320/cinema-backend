package edu.czu.cinema.domain.statistic;

import edu.czu.cinema.common.exception.BusinessException;
import edu.czu.cinema.domain.statistic.dto.RevenueReport.*;
import edu.czu.cinema.domain.statistic.service.RevenueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import static org.assertj.core.api.Assertions.*;

class RevenueServiceTest {
    JdbcTemplate jdbc;
    RevenueService service;
    LocalDate start = LocalDate.of(2024,3,1), end = LocalDate.of(2024,3,4);
    @BeforeEach void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:revenue_"+System.nanoTime()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        jdbc.execute("CREATE TABLE " + (char)96 + "order" + (char)96
                + "(id BIGINT AUTO_INCREMENT PRIMARY KEY,schedule_id BIGINT,total_amount DECIMAL(10,2),status INT,refund_amount DECIMAL(10,2),create_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE movie(id BIGINT PRIMARY KEY,title VARCHAR(255))");
        jdbc.execute("CREATE TABLE hall(id BIGINT PRIMARY KEY,name VARCHAR(255))");
        jdbc.execute("CREATE TABLE schedule(id BIGINT PRIMARY KEY,movie_id BIGINT,hall_id BIGINT)");
        jdbc.execute("CREATE TABLE " + (char)96 + "user" + (char)96 + "(id BIGINT)");
        jdbc.execute("CREATE TABLE " + (char)96 + "type" + (char)96 + "(id BIGINT,name VARCHAR(64))");
        jdbc.execute("CREATE TABLE movie_to_type(movie_id BIGINT,type_id BIGINT)");
        jdbc.update("INSERT INTO movie VALUES(1,'影片甲'),(2,'影片乙')");
        jdbc.update("INSERT INTO hall VALUES(1,'一号厅'),(2,'二号厅')");
        jdbc.update("INSERT INTO schedule VALUES(1,1,1),(2,2,2)");
        jdbc.update("INSERT INTO " + (char)96 + "user" + (char)96 + " VALUES(7),(8)");
        jdbc.update("INSERT INTO " + (char)96 + "type" + (char)96 + " VALUES(1,'悬疑'),(2,'剧情')");
        jdbc.update("INSERT INTO movie_to_type VALUES(1,1),(1,2),(2,2)");
        service = new RevenueService(jdbc);
    }
    void order(long schedule, String date, int status, String gross, String refund) {
        jdbc.update("INSERT INTO " + (char)96 + "order" + (char)96
                        + "(schedule_id,total_amount,status,refund_amount,create_time) VALUES(?,?,?,?,?)",
                schedule,new BigDecimal(gross),status,refund == null ? null : new BigDecimal(refund),date);
    }
    void fixture() {
        order(1,"2024-02-29 23:59:59",2,"100.00",null); // 紧邻上期
        order(1,"2024-03-01 00:00:00",2,"100.01",null);
        order(1,"2024-03-01 20:00:00",3,"200.02","140.01"); // 部分退款留存60.01
        order(1,"2024-03-02 10:00:00",3,"300.03","300.03");
        order(2,"2024-03-02 11:00:00",6,"40.40",null);
        order(1,"2024-03-02 12:00:00",1,"500.00",null);
        order(1,"2024-03-02 13:00:00",4,"600.00",null);
        order(1,"2024-03-02 14:00:00",3,"70.00",null);
        order(1,"2024-03-02 15:00:00",3,"90.00","91.00"); // 异常旧数据不臆造净额
        order(2,"2024-03-04 23:59:59.999",2,"9.99",null);
        order(999,"2024-03-04 22:00:00",2,"20.00",null); // 场次缺失仍保留营收
        order(1,"2024-03-05 00:00:00",2,"999.00",null); // 不含结束日次日零点
    }
    @Test void realAggregationPreservesCentsRefundsLegacyStatesAndUnknownAmounts() {
        fixture();
        var r = service.report(start,end,"day");
        var m = r.summary();
        assertThat(m.grossRevenue()).isEqualByComparingTo("670.45");
        assertThat(m.refundAmount()).isEqualByComparingTo("440.04");
        assertThat(m.netRevenue()).isEqualByComparingTo("230.41");
        assertThat(m.totalOrders()).isEqualTo(10);
        assertThat(m.paidOrders()).isEqualTo(6);
        assertThat(m.refundOrders()).isEqualTo(2);
        assertThat(m.pendingOrders()).isEqualTo(1);
        assertThat(m.closedOrders()).isEqualTo(1);
        assertThat(m.unknownRefundOrders()).isEqualTo(2);
        assertThat(m.unknownRefundOrderAmount()).isEqualByComparingTo("160.00");
        assertThat(m.averageOrderValue()).isEqualByComparingTo("111.74");
        assertThat(m.refundRate()).isEqualByComparingTo("33.33");
        assertThat(r.previousStartDate()).isEqualTo("2024-02-26");
        assertThat(r.previousEndDate()).isEqualTo("2024-02-29");
        assertThat(r.previous().netRevenue()).isEqualByComparingTo("100.00");
        assertThat(r.statusDistribution().stream().mapToLong(Distribution::value).sum()).isEqualTo(m.totalOrders());
    }
    @Test void dailyHolesAreZeroFilledAndAllRepresentationsReconcile() {
        fixture();
        var r = service.report(start,end,"day");
        assertThat(r.daily()).hasSize(4);
        assertThat(r.daily().get(2).metrics().totalOrders()).isZero();
        assertThat(r.daily().getFirst().metrics().netRevenue()).isEqualByComparingTo("160.02");
        assertThat(r.daily().stream().map(p -> p.metrics().netRevenue()).reduce(BigDecimal.ZERO,BigDecimal::add))
                .isEqualByComparingTo(r.summary().netRevenue());
        assertThat(r.movies().stream().map(RankRow::netRevenue).reduce(BigDecimal.ZERO,BigDecimal::add))
                .isEqualByComparingTo(r.summary().netRevenue());
        assertThat(r.halls().stream().map(RankRow::netRevenue).reduce(BigDecimal.ZERO,BigDecimal::add))
                .isEqualByComparingTo(r.summary().netRevenue());
        assertThat(r.movies().getFirst().name()).isEqualTo("影片甲");
        assertThat(r.movies().getFirst().netRevenue()).isEqualByComparingTo("160.02");
        assertThat(r.movies()).anySatisfy(row -> { assertThat(row.id()).isNull(); assertThat(row.netRevenue()).isEqualByComparingTo("20"); });
    }
    @Test void weeksStartOnMondayAndPartialMonthsOnlyCoverSelectedDates() {
        fixture();
        var week = service.report(start,end,"week");
        assertThat(week.trend()).hasSize(2);
        assertThat(week.trend().getFirst().startDate()).isEqualTo(start);
        assertThat(week.trend().getFirst().endDate()).isEqualTo("2024-03-03");
        assertThat(week.trend().getLast().startDate()).isEqualTo("2024-03-04");
        assertThat(week.trend().stream().map(p -> p.metrics().netRevenue()).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("230.41");
        var month = service.report(LocalDate.of(2024,2,28),LocalDate.of(2024,3,2),"month");
        assertThat(month.trend()).hasSize(2);
        assertThat(month.trend().getFirst().startDate()).isEqualTo("2024-02-28");
        assertThat(month.trend().getFirst().endDate()).isEqualTo("2024-02-29");
        assertThat(month.trend().getLast().endDate()).isEqualTo("2024-03-02");
    }
    @Test void emptyDatabaseAndZeroDenominatorsReturnTypedZeroes() {
        var r = service.report(start,end,"day");
        assertThat(r.summary().averageOrderValue()).isEqualByComparingTo("0.00");
        assertThat(r.summary().refundRate()).isEqualByComparingTo("0.00");
        assertThat(r.movies()).isEmpty();
        assertThat(r.daily()).hasSize(4).allSatisfy(p -> assertThat(p.metrics().netRevenue()).isEqualByComparingTo("0.00"));
    }
    @Test void invalidAndOverlyWideDateRangesAreRejectedAndDefaultsUseChinaDate() {
        assertThatThrownBy(() -> service.report(end,start,"day")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.report(start,start.plusDays(366),"day")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.report(start,LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1),"day")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.report(start,end,"day;DROP TABLE movie")).isInstanceOf(BusinessException.class);
        assertThat(service.report(start,start.plusDays(365),"day").daily()).hasSize(366);
        var defaults = service.report(null,null,"day");
        assertThat(defaults.endDate()).isEqualTo(LocalDate.now(ZoneId.of("Asia/Shanghai")));
        assertThat(defaults.daily()).hasSize(30);
    }
    @Test void overviewUsesMatchingContractTrueCountsAndNoGenreRevenueDuplication() {
        fixture();
        var overview = service.overview();
        assertThat(overview.totalMovies()).isEqualTo(2);
        assertThat(overview.totalUsers()).isEqualTo(2);
        assertThat(overview.totalSchedules()).isEqualTo(2);
        assertThat(overview.totalOrders()).isEqualTo(12);
        assertThat(overview.totalRevenue()).isEqualByComparingTo("1329.41");
        assertThat(overview.todayRevenue()).isEqualByComparingTo("0.00");
        assertThat(overview.revenueTrend()).hasSize(7);
        assertThat(overview.typeDistribution()).contains(new Distribution("剧情",2),new Distribution("悬疑",1));
        assertThat(overview.topMovies().getFirst().boxOffice()).isEqualByComparingTo("1259.02");
    }
}
