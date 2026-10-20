package edu.czu.cinema.domain.movie;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import edu.czu.cinema.domain.movie.mapper.*;
import edu.czu.cinema.domain.movie.service.impl.MovieServiceImpl;
import edu.czu.cinema.domain.schedule.mapper.ScheduleMapper;
import edu.czu.cinema.domain.score.mapper.ScoreMapper;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class MovieShowingSqlTest {
    @Test void localShowingDependsOnActiveSchedulesInsteadOfMarketState() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:showing;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
            CREATE TABLE movie(id BIGINT PRIMARY KEY,title VARCHAR(255),summary TEXT,
            score DECIMAL(3,1),release_date DATE,area VARCHAR(64),status INT,create_time TIMESTAMP,
            original_title VARCHAR(255),language VARCHAR(32),runtime_min INT NOT NULL DEFAULT 0,
            poster_url VARCHAR(512),backdrop_url VARCHAR(512),trailer_url VARCHAR(512),cat VARCHAR(255),dir VARCHAR(255),
            source VARCHAR(32),maoyan_id INT,tmdb_id INT,score_source VARCHAR(16),last_synced_at TIMESTAMP,
            show_days INT,first_day_box DECIMAL(12,2),total_box DECIMAL(14,2),fmt VARCHAR(32),
            wish_count INT,score_count INT,update_time TIMESTAMP,manual_fields VARCHAR(255),manual_revision INT,last_repair_at TIMESTAMP)
            """);
        jdbc.execute("CREATE TABLE schedule(id BIGINT PRIMARY KEY,movie_id BIGINT,status INT,start_time TIMESTAMP,end_time TIMESTAMP)");
        jdbc.execute("INSERT INTO movie(id,title,status) VALUES(1,'市场热映但未排片',1),(2,'已撤销排片',1),(3,'老片重映',0),(4,'进行中的场次',0)");
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        config.addMapper(MovieMapper.class); config.addMapper(ScheduleMapper.class);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(ds); factory.setConfiguration(config);
        factory.setMapperLocations(new ClassPathResource("mapper/MovieMapper.xml"),new ClassPathResource("mapper/ScheduleMapper.xml"));
        var pagination = new MybatisPlusInterceptor(); pagination.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        factory.setPlugins(pagination);
        {
            var session = new SqlSessionTemplate(factory.getObject());
            var service = new MovieServiceImpl(session.getMapper(MovieMapper.class),mock(TypeMapper.class),
                    mock(MovieTypeBindMapper.class),mock(UserWantMapper.class),mock(ScoreMapper.class),mock(PersonMapper.class),
                    mock(MovieCrewMapper.class),mock(MovieImageMapper.class),mock(MovieVideoMapper.class),mock(MovieTagMapper.class),
                    mock(MovieAltTitleMapper.class),session.getMapper(ScheduleMapper.class));
            assertThat(service.hotMovies()).isEmpty();
            jdbc.execute("UPDATE movie SET manual_fields='runtimeMin,dir',manual_revision=2 WHERE id=1");
            var locked = session.getMapper(MovieMapper.class).lockById(1L);
            assertThat(locked.getLockedFields()).containsExactly("runtimeMin","dir");
            assertThat(locked.getMarketStatus()).isEqualTo(1);
            assertThat(locked.getManualRevision()).isEqualTo(2);
            assertThat(service.page(1,10,null,null,null,true).getTotal()).isEqualTo(4);
            jdbc.execute("UPDATE movie SET summary='简介',runtime_min=100,release_date='2020-01-02',cat='剧情',dir='导演',area='中国',language='汉语',poster_url='https://oss/poster.jpg' WHERE id=1");
            assertThat(service.page(1,10,null,null,null,true).getRows()).extracting(m -> m.getId()).containsExactlyInAnyOrder(2L,3L,4L);
            jdbc.execute("INSERT INTO schedule VALUES(1,1,1,DATEADD('DAY',-2,CURRENT_TIMESTAMP),DATEADD('DAY',-1,CURRENT_TIMESTAMP)),"
                    + "(2,2,2,DATEADD('DAY',1,CURRENT_TIMESTAMP),DATEADD('DAY',2,CURRENT_TIMESTAMP)),"
                    + "(3,3,1,DATEADD('DAY',1,CURRENT_TIMESTAMP),DATEADD('DAY',2,CURRENT_TIMESTAMP)),"
                    + "(4,4,1,DATEADD('HOUR',-1,CURRENT_TIMESTAMP),DATEADD('HOUR',1,CURRENT_TIMESTAMP))");
            assertThat(service.hotMovies()).extracting(m -> m.getId()).containsExactlyInAnyOrder(3L,4L);
            assertThat(service.hotMovies()).allMatch(m -> m.getHasSchedules() && m.getStatus() == 1 && m.getMarketStatus() == 0);
            assertThat(service.page(1,10,null,null,true).getTotal()).isEqualTo(2);
            assertThat(service.page(1,10,null,null,false).getTotal()).isEqualTo(2);
            jdbc.execute("UPDATE schedule SET status=2 WHERE movie_id=3");
            jdbc.execute("UPDATE schedule SET end_time=DATEADD('MINUTE',-1,CURRENT_TIMESTAMP) WHERE movie_id=4");
            assertThat(service.hotMovies()).isEmpty();
        }
    }
}
