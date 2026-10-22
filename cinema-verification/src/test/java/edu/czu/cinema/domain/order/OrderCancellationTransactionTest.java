package edu.czu.cinema.domain.order;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.czu.cinema.common.exception.BusinessException;
import edu.czu.cinema.domain.order.mapper.OrderMapper;
import edu.czu.cinema.domain.order.service.OrderAuditService;
import edu.czu.cinema.domain.order.service.OrderCloseHelper;
import edu.czu.cinema.domain.ticket.service.TicketService;
import edu.czu.cinema.remote.OperationJournal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

class OrderCancellationTransactionTest {
    JdbcTemplate jdbc;
    TransactionTemplate tx;
    OrderCloseHelper close;
    OperationJournal journal;

    @BeforeEach
    void setup() throws Exception {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:cancel_tx_"
                                + System.nanoTime()
                                + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute(
                "CREATE TABLE `order`(id BIGINT PRIMARY KEY,number VARCHAR(64),user_id"
                    + " BIGINT,schedule_id BIGINT,total_amount DECIMAL(10,2),status"
                    + " INT,refund_amount DECIMAL(10,2),refund_time TIMESTAMP,pay_time"
                    + " TIMESTAMP,pay_channel VARCHAR(32),create_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE ticket(id BIGINT PRIMARY KEY,order_id BIGINT,status INT)");
        jdbc.execute("CREATE TABLE close_audit(order_id BIGINT)");
        jdbc.execute(
                "CREATE TABLE service_operation(operation_id VARCHAR(64) PRIMARY KEY,user_id"
                    + " BIGINT,operation_type VARCHAR(64),request_hash VARCHAR(64),result_json"
                    + " VARCHAR(4000))");
        jdbc.update("INSERT INTO `order`(id,number,user_id,status) VALUES(9,'O9',7,1)");
        jdbc.update("INSERT INTO ticket VALUES(1,9,0)");
        var manager = new DataSourceTransactionManager(ds);
        tx = new TransactionTemplate(manager);
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(OrderMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        var mapper = new SqlSessionTemplate(factory.getObject()).getMapper(OrderMapper.class);
        var tickets = mock(TicketService.class);
        when(tickets.releaseTickets(anyLong()))
                .thenAnswer(
                        i ->
                                jdbc.update(
                                        "UPDATE ticket SET order_id=-1,status=1 WHERE order_id=?",
                                        (Long) i.getArgument(0)) > 0);
        var audit = mock(OrderAuditService.class);
        doAnswer(
                        i -> {
                            jdbc.update(
                                    "INSERT INTO close_audit VALUES(?)", (Long) i.getArgument(0));
                            return null;
                        })
                .when(audit)
                .record(any(), any(), any(), any(), any(), any(), any(), any(), any());
        var proxy = new ProxyFactory(new OrderCloseHelper(mapper, tickets, audit));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(
                new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        close = (OrderCloseHelper) proxy.getProxy();
        journal = new OperationJournal(jdbc, new ObjectMapper());
    }

    @Test
    void userCancellationAndReplayRecordRollBackTogether() {
        assertThatThrownBy(
                        () ->
                                tx.execute(
                                        s ->
                                                journal.run(
                                                        "cancel",
                                                        7L,
                                                        "CANCEL",
                                                        9L,
                                                        Boolean.class,
                                                        () -> {
                                                            close.closePendingInTransaction(
                                                                    9L, "cancel", "USER", 7L);
                                                            throw new BusinessException(
                                                                    "simulate failure before result"
                                                                        + " commit");
                                                        })))
                .isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM `order` WHERE id=9", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT order_id FROM ticket WHERE id=1", Long.class))
                .isEqualTo(9L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM close_audit", Long.class)).isZero();
        assertThat(journal.result("cancel", 7L)).isNull();
        Boolean committed =
                tx.execute(
                        s ->
                                journal.run(
                                        "cancel",
                                        7L,
                                        "CANCEL",
                                        9L,
                                        Boolean.class,
                                        () ->
                                                close.closePendingInTransaction(
                                                        9L, "cancel", "USER", 7L)));
        assertThat(committed).isTrue();
        assertThat(journal.result("cancel", 7L)).isEqualTo("true");
    }

    @Test
    void expiryCleanupStillCommitsWhenThePaymentCallerRollsBack() {
        assertThatThrownBy(
                        () ->
                                tx.execute(
                                        s -> {
                                            close.closePending(9L, "expired", "SYSTEM", null);
                                            throw new BusinessException("reject expired payment");
                                        }))
                .isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM `order` WHERE id=9", Integer.class))
                .isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT order_id FROM ticket WHERE id=1", Long.class))
                .isEqualTo(-1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM close_audit", Long.class))
                .isEqualTo(1);
    }
}
