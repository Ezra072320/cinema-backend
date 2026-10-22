package edu.czu.cinema.remote;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.czu.cinema.common.exception.BusinessException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

class OperationJournalTest {
    JdbcTemplate jdbc;
    OperationJournal journal;
    TransactionTemplate tx;

    @BeforeEach
    void setup() {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:journal_"
                                + System.nanoTime()
                                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
                        "sa",
                        "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute(
                "CREATE TABLE service_operation(operation_id VARCHAR(64) PRIMARY KEY,user_id"
                    + " BIGINT,operation_type VARCHAR(64),request_hash VARCHAR(64),result_json"
                    + " VARCHAR(4000))");
        jdbc.execute("CREATE TABLE business_effect(id BIGINT PRIMARY KEY)");
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        journal = new OperationJournal(jdbc, new ObjectMapper());
    }

    @Test
    void retryAfterLostResponseReplaysTheCommittedResult() {
        var calls = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            Long result =
                    tx.execute(
                            status ->
                                    journal.run(
                                            "request",
                                            7L,
                                            "CREATE",
                                            List.of(9L),
                                            Long.class,
                                            () -> {
                                                calls.incrementAndGet();
                                                jdbc.update(
                                                        "INSERT INTO business_effect VALUES(9)");
                                                return 9L;
                                            }));
            assertThat(result).isEqualTo(9L);
        }
        assertThat(calls).hasValue(1);
        assertThat(journal.result("request", 7L)).isEqualTo("9");
        assertThat(journal.result("request", 8L)).isNull();
    }

    @Test
    void changingActorOrPayloadCannotReuseAnOperation() {
        tx.execute(s -> journal.run("request", 7L, "CREATE", 9L, Long.class, () -> 9L));
        assertThatThrownBy(
                        () ->
                                tx.execute(
                                        s ->
                                                journal.run(
                                                        "request",
                                                        8L,
                                                        "CREATE",
                                                        9L,
                                                        Long.class,
                                                        () -> 10L)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(
                        () ->
                                tx.execute(
                                        s ->
                                                journal.run(
                                                        "request",
                                                        7L,
                                                        "CREATE",
                                                        10L,
                                                        Long.class,
                                                        () -> 10L)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void failureRollsBackBothJournalAndBusinessEffect() {
        assertThatThrownBy(
                        () ->
                                tx.execute(
                                        s ->
                                                journal.run(
                                                        "request",
                                                        7L,
                                                        "CREATE",
                                                        9L,
                                                        Long.class,
                                                        () -> {
                                                            jdbc.update(
                                                                    "INSERT INTO business_effect"
                                                                        + " VALUES(9)");
                                                            throw new BusinessException("fail");
                                                        })))
                .isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM business_effect", Long.class))
                .isZero();
        assertThat(journal.result("request", 7L)).isNull();
    }

    @Test
    void concurrentDuplicateRequestsExecuteOnlyOnce() throws Exception {
        var pool = Executors.newFixedThreadPool(8);
        var gate = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try {
            var tasks = new java.util.ArrayList<Future<Long>>();
            for (int i = 0; i < 8; i++)
                tasks.add(
                        pool.submit(
                                () -> {
                                    gate.await();
                                    return tx.execute(
                                            s ->
                                                    journal.run(
                                                            "request",
                                                            7L,
                                                            "CREATE",
                                                            9L,
                                                            Long.class,
                                                            () -> {
                                                                calls.incrementAndGet();
                                                                jdbc.update(
                                                                        "INSERT INTO"
                                                                            + " business_effect"
                                                                            + " VALUES(9)");
                                                                return 9L;
                                                            }));
                                }));
            gate.countDown();
            for (var task : tasks) assertThat(task.get(15, TimeUnit.SECONDS)).isEqualTo(9L);
            assertThat(calls).hasValue(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
