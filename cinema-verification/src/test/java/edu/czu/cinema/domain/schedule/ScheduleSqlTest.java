package edu.czu.cinema.domain.schedule;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import edu.czu.cinema.domain.schedule.mapper.ScheduleMapper;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * 排片 SQL 语义(H2): 已取消场次不挡冲突、不挡座位图发布、退出本店热映; 过期场次同样退出。
 */
class ScheduleSqlTest {

    private static SqlSessionTemplate openSession(DriverManagerDataSource ds, String... mappers) throws Exception {
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(ScheduleMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        factory.setMapperLocations(java.util.Arrays.stream(mappers)
                .map(m -> (org.springframework.core.io.Resource) new ClassPathResource(m))
                .toArray(org.springframework.core.io.Resource[]::new));
        return new SqlSessionTemplate(factory.getObject());
    }

    @Test
    void canceledSchedulesReleaseConflictAndSeatMap() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:schedsql;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        // 表结构与 ScheduleMapper 的 listConflict(BY MP LambdaQueryWrapper, 会 SELECT 全列)对齐,
        // 必须有 batch_id 列 —— 否则生成列清单里带 batch_id 会在 H2 上翻车(2026-10-20 改 MP 起).
        jdbc.execute("""
            CREATE TABLE schedule(id BIGINT PRIMARY KEY,movie_id BIGINT,hall_id BIGINT,
            seat_version_id BIGINT,start_time TIMESTAMP,end_time TIMESTAMP,price DECIMAL(8,2),
            vip_price_multiplier DECIMAL(3,2),couple_price_multiplier DECIMAL(3,2),status INT,batch_id VARCHAR(64))
            """);
        var session = openSession(ds, "mapper/ScheduleMapper.xml");
        var mapper = session.getMapper(ScheduleMapper.class);

        LocalDateTime now = LocalDateTime.now();
        // 场次 1: 正常占用今天 10:00-12:00; 场次 2: 同时段但已取消
        jdbc.execute("INSERT INTO schedule VALUES(1,1,1,1,DATEADD('HOUR',1,CURRENT_TIMESTAMP),DATEADD('HOUR',3,CURRENT_TIMESTAMP),50,3,2.5,1,NULL)");
        jdbc.execute("INSERT INTO schedule VALUES(2,2,1,1,DATEADD('HOUR',1,CURRENT_TIMESTAMP),DATEADD('HOUR',3,CURRENT_TIMESTAMP),50,3,2.5,2,NULL)");

        // 1) 已取消场次不挡冲突检测
        assertThat(mapper.listConflict(1L, now.plusHours(1), now.plusHours(3), null))
                .extracting(s -> s.getId()).containsExactly(1L);

        // 2) 已取消场次不挡座位图发布(取消即解锁)
        assertThat(mapper.countFutureByHall(1L)).isEqualTo(1L);
        jdbc.execute("UPDATE schedule SET status=2 WHERE id=1");
        assertThat(mapper.countFutureByHall(1L)).isZero();

        // 3) 本店热映: 取消与过期都退出
        jdbc.execute("INSERT INTO schedule VALUES(3,3,1,1,DATEADD('DAY',1,CURRENT_TIMESTAMP),DATEADD('DAY',2,CURRENT_TIMESTAMP),50,3,2.5,1,NULL)");
        jdbc.execute("INSERT INTO schedule VALUES(4,4,1,1,DATEADD('DAY',-2,CURRENT_TIMESTAMP),DATEADD('DAY',-1,CURRENT_TIMESTAMP),50,3,2.5,1,NULL)");
        var summary = mapper.movieShowSummaries(List.of(1L, 2L, 3L, 4L));
        assertThat(summary).extracting(s -> s.getMovieId()).containsExactly(3L);
    }

    @Test
    void batchQueryExcludesCanceledSessions() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:schedbatch;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
            CREATE TABLE schedule(id BIGINT PRIMARY KEY,movie_id BIGINT,hall_id BIGINT,
            seat_version_id BIGINT,start_time TIMESTAMP,end_time TIMESTAMP,price DECIMAL(8,2),
            vip_price_multiplier DECIMAL(3,2),couple_price_multiplier DECIMAL(3,2),status INT,
            batch_id VARCHAR(36))
            """);
        var session = openSession(ds, "mapper/ScheduleMapper.xml");
        var mapper = session.getMapper(ScheduleMapper.class);
        jdbc.execute("INSERT INTO schedule VALUES(1,1,1,1,DATEADD('DAY',1,CURRENT_TIMESTAMP),DATEADD('DAY',2,CURRENT_TIMESTAMP),50,3,2.5,1,'b1')");
        jdbc.execute("INSERT INTO schedule VALUES(2,1,1,1,DATEADD('DAY',2,CURRENT_TIMESTAMP),DATEADD('DAY',3,CURRENT_TIMESTAMP),50,3,2.5,2,'b1')");
        jdbc.execute("INSERT INTO schedule VALUES(3,1,1,1,DATEADD('DAY',3,CURRENT_TIMESTAMP),DATEADD('DAY',4,CURRENT_TIMESTAMP),50,3,2.5,1,'b2')");

        // batchMove/batchInfo 的取数口径: batch_id + status=1 —— 已取消(2)的不进改期范围
        var rows = mapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<
                        edu.czu.cinema.domain.schedule.entity.Schedule>()
                .eq(edu.czu.cinema.domain.schedule.entity.Schedule::getBatchId, "b1")
                .eq(edu.czu.cinema.domain.schedule.entity.Schedule::getStatus, 1));
        assertThat(rows).extracting(r -> r.getId()).containsExactly(1L);
    }
}
