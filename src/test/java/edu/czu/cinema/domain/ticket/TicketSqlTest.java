package edu.czu.cinema.domain.ticket;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import edu.czu.cinema.domain.ticket.entity.Ticket;
import edu.czu.cinema.domain.ticket.mapper.TicketMapper;
import edu.czu.cinema.domain.ticket.service.impl.TicketServiceImpl;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * 票 SQL 语义(H2): 取消场次作废(voidBySchedule)后保留 order_id 且不再算已售/占用;
 * 占用语义 = status=0; releaseTickets(用户退票)回到可售并解绑.
 * 2026-10-20 起 TicketMapper 改纯 MP(BaseMapper), 由服务层用条件构造器组 SQL.
 */
class TicketSqlTest {

    private static SqlSessionTemplate openSession(DriverManagerDataSource ds) throws Exception {
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(TicketMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        return new SqlSessionTemplate(factory.getObject());
    }

    @Test
    void voidByScheduleKeepsOrderIdAndIsNotCountedAsOccupied() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:ticketvoid;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
            CREATE TABLE ticket(id BIGINT AUTO_INCREMENT PRIMARY KEY,schedule_id BIGINT,seat_id BIGINT,
            order_id BIGINT DEFAULT -1,status INT DEFAULT 1,price DECIMAL(8,2))
            """);
        var session = openSession(ds);
        var mapper = session.getMapper(TicketMapper.class);
        var service = new TicketServiceImpl(mapper);

        // 两张票同场: t1 被订单锁定占座, t2 仍是可售
        Ticket t1 = new Ticket();
        t1.setScheduleId(1L); t1.setSeatId(10L); t1.setStatus(1); t1.setOrderId(-1L); t1.setPrice(new java.math.BigDecimal("60.00"));
        Ticket t2 = new Ticket();
        t2.setScheduleId(1L); t2.setSeatId(11L); t2.setStatus(1); t2.setOrderId(-1L); t2.setPrice(new java.math.BigDecimal("60.00"));
        mapper.insert(t1); mapper.insert(t2);
        Long id1 = t1.getId();

        assertThat(service.lockTickets(List.of(id1), 100L)).isEqualTo(1);
        // 锁单后: 只有 status=0 的票算"挂在订单上"
        assertThat(service.countOrderedBySchedule(1L)).isEqualTo(1);
        assertThat(service.countSoldBySchedule(1L)).isEqualTo(1);

        // 场次取消 → 全场作废(status=2), 但 order_id 保留
        assertThat(service.voidBySchedule(1L)).isEqualTo(2);
        Ticket after = mapper.selectById(id1);
        assertThat(after.getStatus()).isEqualTo(2);
        assertThat(after.getOrderId()).isEqualTo(100L); // 已退款订单还能回看座位

        // 作废票(status=2)不再算占用/已售 → 「取消后场次可删」与「已售数」口径一致
        assertThat(service.countOrderedBySchedule(1L)).isZero();
        assertThat(service.countSoldBySchedule(1L)).isZero();
    }

    @Test
    void releaseTicketsReturnsToAvailableAndUnbinds() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:ticketrel;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
            CREATE TABLE ticket(id BIGINT AUTO_INCREMENT PRIMARY KEY,schedule_id BIGINT,seat_id BIGINT,
            order_id BIGINT DEFAULT -1,status INT DEFAULT 1,price DECIMAL(8,2))
            """);
        var session = openSession(ds);
        var mapper = session.getMapper(TicketMapper.class);
        var service = new TicketServiceImpl(mapper);

        Ticket t = new Ticket();
        t.setScheduleId(1L); t.setSeatId(20L); t.setStatus(1); t.setOrderId(-1L);
        mapper.insert(t);
        service.lockTickets(List.of(t.getId()), 50L);

        // 用户退票: 票回可售, order_id 回哨兵 -1(语义不变)
        assertThat(service.releaseTickets(50L)).isTrue();
        Ticket after = mapper.selectById(t.getId());
        assertThat(after.getStatus()).isEqualTo(1);
        assertThat(after.getOrderId()).isEqualTo(-1L);
        assertThat(service.countOrderedBySchedule(1L)).isZero();

        // 删除场次票: 全清
        assertThat(service.deleteBySchedule(1L)).isEqualTo(1);
        assertThat(mapper.selectList(null)).isEmpty();
    }
}