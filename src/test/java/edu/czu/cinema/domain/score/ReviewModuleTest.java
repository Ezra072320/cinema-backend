package edu.czu.cinema.domain.score;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import edu.czu.cinema.common.exception.BusinessException;
import edu.czu.cinema.domain.hall.mapper.HallMapper;
import edu.czu.cinema.domain.movie.entity.Movie;
import edu.czu.cinema.domain.movie.mapper.MovieMapper;
import edu.czu.cinema.domain.order.mapper.OrderMapper;
import edu.czu.cinema.domain.schedule.mapper.ScheduleMapper;
import edu.czu.cinema.domain.score.dto.ScoreSubmitDTO;
import edu.czu.cinema.domain.score.mapper.ScoreMapper;
import edu.czu.cinema.domain.score.service.ReviewQueryService;
import edu.czu.cinema.domain.score.service.impl.ScoreServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** H2+真实MyBatis+事务，覆盖完整增改删、归属、统计和并发。 */
class ReviewModuleTest {
    JdbcTemplate jdbc;
    TransactionTemplate tx;
    ScoreServiceImpl service;
    ReviewQueryService queries;
    MovieMapper movies;
    @BeforeEach void setup() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:reviews_"+System.nanoTime()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000","sa","");
        jdbc = new JdbcTemplate(ds); tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("CREATE TABLE movie(id BIGINT PRIMARY KEY,title VARCHAR(255),poster_url VARCHAR(255),runtime_min INT)");
        jdbc.execute("CREATE TABLE " + (char)96 + "user" + (char)96 + "(id BIGINT PRIMARY KEY,name VARCHAR(255))");
        jdbc.execute("CREATE TABLE schedule(id BIGINT PRIMARY KEY,movie_id BIGINT,hall_id BIGINT,seat_version_id BIGINT,start_time TIMESTAMP,end_time TIMESTAMP,price DECIMAL(8,2),vip_price_multiplier DECIMAL(8,2),couple_price_multiplier DECIMAL(8,2),status INT,batch_id VARCHAR(64))");
        jdbc.execute("CREATE TABLE " + (char)96 + "order" + (char)96 + "(id BIGINT PRIMARY KEY,number VARCHAR(64),user_id BIGINT,schedule_id BIGINT,total_amount DECIMAL(10,2),status INT,refund_amount DECIMAL(10,2),refund_time TIMESTAMP,pay_time TIMESTAMP,pay_channel VARCHAR(32),create_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE score(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT,movie_id BIGINT,order_id BIGINT UNIQUE NOT NULL,score INT,content VARCHAR(1024),create_time TIMESTAMP,update_time TIMESTAMP,version BIGINT NOT NULL DEFAULT 1)");
        jdbc.update("INSERT INTO movie VALUES(1,'测试电影','',100)");
        jdbc.update("INSERT INTO " + (char)96 + "user" + (char)96 + " VALUES(7,'观影甲'),(8,'观影乙')");
        var start = LocalDateTime.now().minusMinutes(120);
        jdbc.update("INSERT INTO schedule(id,movie_id,hall_id,start_time,end_time,status) VALUES(1,1,1,?,?,1)",start,start.plusMinutes(130));
        jdbc.update("INSERT INTO " + (char)96 + "order" + (char)96 + "(id,number,user_id,schedule_id,total_amount,status,refund_amount,refund_time,create_time) VALUES(1,'O1',7,1,50,2,NULL,NULL,?)",start.minusDays(1));
        jdbc.update("INSERT INTO " + (char)96 + "order" + (char)96 + "(id,number,user_id,schedule_id,total_amount,status,refund_amount,refund_time,create_time) VALUES(2,'O2',8,1,50,2,NULL,NULL,?)",start.minusDays(1));
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        config.addMapper(OrderMapper.class); config.addMapper(ScoreMapper.class); config.addMapper(ScheduleMapper.class);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(ds); factory.setConfiguration(config);
        var session = new SqlSessionTemplate(factory.getObject());
        movies = mock(MovieMapper.class);
        var movie = new Movie(); movie.setId(1L); movie.setTitle("测试电影"); movie.setRuntimeMin(100);
        when(movies.selectById(1L)).thenReturn(movie);
        queries = new ReviewQueryService(jdbc);
        service = new ScoreServiceImpl(session.getMapper(ScoreMapper.class),session.getMapper(OrderMapper.class),
                session.getMapper(ScheduleMapper.class),movies,mock(HallMapper.class),queries);
    }
    void save(long user, long order, int stars, String text, long version) {
        tx.executeWithoutResult(s -> service.submit(user,new ScoreSubmitDTO(order,stars,text,version)));
    }
    int orderStatus() { return jdbc.queryForObject("SELECT status FROM " + (char)96 + "order" + (char)96 + " WHERE id=1",Integer.class); }

    @Test void publishEditDeleteUpdatesIndependentLocalRatingsAndNeverChangesOrder() {
        save(7,1,5,"  很好看  ",0);
        assertThat(orderStatus()).isEqualTo(2);
        var first = service.ownedReview(7L,1L);
        assertThat(first.getContent()).isEqualTo("很好看");
        assertThat(first.getCreateTime()).isNotNull();
        assertThat(queries.movie(1L,null,1,10,"newest").summary().average()).isEqualByComparingTo("5.0");
        save(7,1,3,"修改后的感受",1);
        var edited = service.ownedReview(7L,1L);
        assertThat(edited.getVersion()).isEqualTo(2);
        assertThat(edited.getCreateTime()).isEqualTo(first.getCreateTime());
        assertThat(queries.movie(1L,null,1,10,"newest").summary().average()).isEqualByComparingTo("3.0");
        assertThatThrownBy(() -> save(7,1,1,"旧窗口覆盖",1)).hasMessageContaining("修改");
        tx.executeWithoutResult(s -> service.remove(7L,1L,2L));
        assertThat(queries.movie(1L,null,1,10,"newest").total()).isZero();
        assertThat(orderStatus()).isEqualTo(2);
        save(7,1,4,"重新发表",0);
        assertThat(queries.mine(7L,1,10).getTotal()).isEqualTo(1);
        verify(movies,never()).updateById(any(Movie.class));
    }
    @Test void ownerIsCheckedForReadWriteContextAndDeletion() {
        save(7,1,4,"属于甲的短评",0);
        assertThatThrownBy(() -> service.ownedReview(8L,1L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.context(8L,1L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> save(8,1,1,"越权",1)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> service.remove(8L,1L,1L))).isInstanceOf(BusinessException.class);
        assertThat(queries.mine(8L,1,10).getRows()).isEmpty();
        var publicReview = queries.movie(1L,null,1,10,"newest").rows().get(0);
        assertThat(publicReview.getOrderId()).isNull(); assertThat(publicReview.getVersion()).isNull();
        assertThat(publicReview.getAuthor()).isEqualTo("观***");
    }
    @Test void futureAndRefundedOrdersCannotPublishAndTheirLegacyReviewsAreHidden() {
        save(7,1,4,"旧评价",0);
        jdbc.update("UPDATE schedule SET start_time=?,end_time=? WHERE id=1",LocalDateTime.now().plusHours(4),LocalDateTime.now().plusHours(6));
        assertThatThrownBy(() -> save(7,1,5,"提前修改",1)).hasMessageContaining("结束后");
        assertThat(queries.movie(1L,null,1,10,"newest").total()).isZero();
        assertThat(queries.mine(7L,1,10).getRows().get(0).isPublished()).isFalse();
        jdbc.update("UPDATE " + (char)96 + "order" + (char)96 + " SET status=3 WHERE id=1");
        assertThatThrownBy(() -> save(7,1,5,"退款后修改",1)).isInstanceOf(BusinessException.class);
        tx.executeWithoutResult(s -> service.remove(7L,1L,1L)); // 可清理自己的历史无效评价
        assertThat(service.ownedReview(7L,1L)).isNull();
    }
    @Test void cancelledAndInvalidReviewsDoNotAffectAverage() {
        save(7,1,5,"A",0); save(8,2,1,"B",0);
        assertThat(queries.movie(1L,null,1,10,"newest").summary().average()).isEqualByComparingTo("3.0");
        jdbc.update("UPDATE score SET score=NULL WHERE order_id=2");
        assertThat(queries.movie(1L,null,1,10,"newest").summary().count()).isEqualTo(1);
        jdbc.update("UPDATE schedule SET status=2 WHERE id=1");
        assertThat(queries.movie(1L,null,1,10,"newest").summary().count()).isZero();
        assertThatThrownBy(() -> save(7,1,3,"不能改",1)).hasMessageContaining("取消");
    }
    @Test void concurrentCreateAndEditCannotDuplicateOrSilentlyOverwrite() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try {
            var barrier = new CyclicBarrier(2);
            Callable<Boolean> create = () -> { barrier.await(); try { save(7,1,4,"并发",0); return true; } catch (BusinessException e) { return false; } };
            List<Future<Boolean>> results = pool.invokeAll(List.of(create,create));
            assertThat(results.get(0).get() ^ results.get(1).get()).isTrue();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM score",Long.class)).isEqualTo(1);
            Callable<Boolean> edit = () -> { barrier.await(); try { save(7,1,2,"修改",1); return true; } catch (BusinessException e) { return false; } };
            results = pool.invokeAll(List.of(edit,edit));
            assertThat(results.get(0).get() ^ results.get(1).get()).isTrue();
            assertThat(service.ownedReview(7L,1L).getVersion()).isEqualTo(2);
            assertThat(orderStatus()).isEqualTo(2);
        } finally { pool.shutdownNow(); }
    }
    @Test void validationPaginationAndSortRejectInvalidInput() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> service.submit(7L,new ScoreSubmitDTO(1L,null,"",0L)))).hasMessageContaining("1至5");
        assertThatThrownBy(() -> save(7,1,0,"",0)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> save(7,1,6,"",0)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> save(7,1,4,"字".repeat(1025),0)).hasMessageContaining("1024");
        assertThatThrownBy(() -> queries.mine(7L,0,10)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> queries.movie(1L,null,1,10,"' OR 1=1")).isInstanceOf(BusinessException.class);
    }
}
