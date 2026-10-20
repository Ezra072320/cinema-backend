package edu.czu.cinema.ai;

import edu.czu.cinema.ai.service.AiCatalogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;

/** 实际SQL测试：不连接开发库，不调用模型。 */
class AiCatalogServiceTest {
    JdbcTemplate jdbc;
    AiCatalogService service;
    @BeforeEach void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:ai_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        service = new AiCatalogService(jdbc);
        jdbc.execute("CREATE TABLE movie(id BIGINT,title VARCHAR,original_title VARCHAR,cat VARCHAR,dir VARCHAR,area VARCHAR,language VARCHAR,runtime_min INT,score DECIMAL,score_source VARCHAR,release_date DATE)");
        jdbc.execute("CREATE TABLE person(id BIGINT,name VARCHAR)");
        jdbc.execute("CREATE TABLE movie_crew(movie_id BIGINT,person_id BIGINT)");
        jdbc.execute("CREATE TABLE movie_alt_title(movie_id BIGINT,title VARCHAR)");
        jdbc.execute("CREATE TABLE hall(id BIGINT,name VARCHAR,type INT)");
        jdbc.execute("CREATE TABLE schedule(id BIGINT,movie_id BIGINT,hall_id BIGINT,status INT,start_time TIMESTAMP,end_time TIMESTAMP,price DECIMAL)");
        jdbc.execute("CREATE TABLE ticket(id BIGINT,schedule_id BIGINT,status INT,price DECIMAL)");
        jdbc.execute("CREATE TABLE " + (char)96 + "order" + (char)96 + "(id BIGINT,user_id BIGINT,schedule_id BIGINT,number VARCHAR,status INT,create_time TIMESTAMP)");
        jdbc.update("INSERT INTO movie VALUES(1,'星空','Star','科幻','王导演','中国','中文',100,8,'测试',?)", LocalDate.now());
        jdbc.update("INSERT INTO person VALUES(1,'陈演员')");
        jdbc.update("INSERT INTO movie_crew VALUES(1,1)");
        jdbc.update("INSERT INTO movie_alt_title VALUES(1,'宇宙别名')");
        jdbc.update("INSERT INTO hall VALUES(1,'IMAX厅',3)");
    }
    void schedule(long id, int status, LocalDateTime start) {
        jdbc.update("INSERT INTO schedule VALUES(?,1,1,?,?,?,50)", id, status, start, start.plusHours(2));
        jdbc.update("INSERT INTO ticket VALUES(?,?,1,40)", id, id);
    }
    @Test void searchesNamesGenresActorsAndAliasesWithBoundParameters() {
        for (String q : new String[]{"星空","科幻","王导演","陈演员","宇宙别名","Star"})
            assertThat(service.movies(q, null, 1).get("total")).isEqualTo(1L);
        assertThat(service.movies("' OR 1=1 --", null, 1).get("total")).isEqualTo(0L);
    }
    @Test void excludesCancelledAndStartedSchedulesAndReturnsRealTicketMinimum() {
        LocalDateTime future = LocalDate.now().plusDays(1).atTime(19,0);
        schedule(1, 1, future); schedule(2, 2, future);
        schedule(3, 1, LocalDateTime.now().minusHours(1));
        var result = service.schedules("明天", null, null, "18:00", "20:00", new BigDecimal("45"), 1);
        assertThat(result.get("total")).isEqualTo(1L);
        @SuppressWarnings("unchecked") var rows = (java.util.List<java.util.Map<String,Object>>)result.get("rows");
        assertThat(rows.get(0).get("estimatedMovieEndTime").toString()).contains("20:40");
        assertThat(rows.get(0)).doesNotContainKey("slotEndTime");
        assertThat(service.schedules("明天", null, null, null, null, new BigDecimal("30"), 1).get("total")).isEqualTo(0L);
        assertThat(service.movies(null, true, 1).get("total")).isEqualTo(1L);
    }
    @Test void supportsPagingWithoutCallingFirstPageTheWholeDay() {
        for (int i = 1; i <= 15; i++) schedule(i, 1, LocalDate.now().plusDays(1).atTime(10,0).plusMinutes(i));
        var page1 = service.schedules("明天", null, null, null, null, null, 1);
        var page2 = service.schedules("明天", null, null, null, null, null, 2);
        assertThat(page1.get("total")).isEqualTo(15L);
        assertThat(page1.get("hasMore")).isEqualTo(true);
        assertThat((java.util.List<?>)page2.get("rows")).hasSize(3);
    }
    @Test void orderSearchIsAlwaysScopedToCurrentUser() {
        String insert = "INSERT INTO " + (char)96 + "order" + (char)96 + " VALUES(?,?,1,?,2,?)";
        jdbc.update(insert, 1, 7, "MY-ORDER", LocalDateTime.now());
        jdbc.update(insert, 2, 8, "FOREIGN", LocalDateTime.now());
        assertThat(service.orders(7L, 5, null, 1).get("total")).isEqualTo(1L);
        assertThat(service.orders(7L, 5, "FOREIGN", 1).get("total")).isEqualTo(0L);
    }
    @Test void invalidDateDoesNotSilentlyBecomeToday() {
        assertThatThrownBy(() -> AiCatalogService.resolveDate("2026-02-30")).hasMessageContaining("日期");
        assertThatThrownBy(() -> service.movies(null, null, 0)).hasMessageContaining("页码");
    }
}
