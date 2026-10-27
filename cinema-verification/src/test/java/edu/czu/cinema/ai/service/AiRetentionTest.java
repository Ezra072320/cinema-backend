package edu.czu.cinema.ai.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import edu.czu.cinema.ai.mapper.AiActionMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class AiRetentionTest {
    @BeforeAll static void metadata() {
        var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        var assistant = new com.baomidou.mybatisplus.core.MybatisMapperBuilderAssistant(configuration,"retention-test");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant,edu.czu.cinema.ai.entity.AiAction.class);
    }
    JdbcTemplate jdbc;
    AiRetentionStore store;
    LocalDateTime threshold = LocalDateTime.of(2026,4,1,0,0);
    AiRetentionStore.Candidate old = new AiRetentionStore.Candidate("old",1);
    @BeforeEach void setup() {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:retention"+System.nanoTime()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE ai_session(chat_id VARCHAR(64) PRIMARY KEY,user_id BIGINT,update_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE ai_message(chat_id VARCHAR(64),user_id BIGINT)");
        jdbc.execute("CREATE TABLE ai_action(chat_id VARCHAR(64),user_id BIGINT)");
        store = new AiRetentionStore(jdbc,new TransactionTemplate(new DataSourceTransactionManager(ds)));
        jdbc.update("INSERT INTO ai_session VALUES('old',1,?)",threshold.minusDays(1));
        jdbc.update("INSERT INTO ai_message VALUES('old',1)");
        jdbc.update("INSERT INTO ai_action VALUES('old',1)");
    }
    int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class); }
    @Test void purgesAllThreeTablesAsOneUnitAndRejectsWrongOwner() {
        assertThat(store.purge(new AiRetentionStore.Candidate("old",2),threshold)).isFalse();
        assertThat(count("ai_message")).isEqualTo(1);
        assertThat(store.purge(old,threshold)).isTrue();
        assertThat(count("ai_session")+count("ai_message")+count("ai_action")).isZero();
    }
    @Test void rechecksActivityAfterCandidateWasSelected() {
        assertThat(store.candidates(threshold)).containsExactly(old);
        jdbc.update("UPDATE ai_session SET update_time=? WHERE chat_id='old'",threshold.plusDays(1));
        assertThat(store.purge(old,threshold)).isFalse();
        assertThat(count("ai_message")).isEqualTo(1);
    }
    @Test void laterDeleteFailureRollsBackEarlierChildDelete() {
        // A referencing table makes the last delete fail, after both child DELETEs ran.
        jdbc.execute("CREATE TABLE blocker(chat_id VARCHAR(64) REFERENCES ai_session(chat_id))");
        jdbc.update("INSERT INTO blocker VALUES('old')");
        assertThatThrownBy(() -> store.purge(old,threshold)).isInstanceOf(RuntimeException.class);
        assertThat(count("ai_action")).isEqualTo(1);
        assertThat(count("ai_message")).isEqualTo(1);
        assertThat(count("ai_session")).isEqualTo(1);
    }
    @Test void busySessionIsSkippedAndStorageIsNeverCalledWithoutLease() {
        var mockedStore = mock(AiRetentionStore.class);
        when(mockedStore.candidates(any())).thenReturn(List.of(old));
        var admission = mock(DistributedGenerationAdmission.class);
        var job = new AiDataRetentionJob(mock(AiActionMapper.class),mockedStore,admission);
        assertThat(job.runCleanup().skipped()).isEqualTo(1);
        verify(mockedStore,never()).purge(any(),any());
    }
    @Test void failedItemProducesFailureForSchedulerAndLeavesCommittedItemsRetryable() {
        var mockedStore = mock(AiRetentionStore.class);
        when(mockedStore.candidates(any())).thenReturn(List.of(old));
        var admission = mock(DistributedGenerationAdmission.class);
        when(admission.whileSessionIdle(any(),any(),any())).thenThrow(new IllegalStateException("Redis down"));
        var job = new AiDataRetentionJob(mock(AiActionMapper.class),mockedStore,admission);
        assertThat(job.runCleanup().failed()).isEqualTo(1);
        assertThatThrownBy(job::cleanup).isInstanceOf(IllegalStateException.class);
    }
    @Test void cancellationDoesNotBeginAnotherCleanupAndRetainsInterruptSignal() {
        var actionMapper = mock(AiActionMapper.class);
        var mockedStore = mock(AiRetentionStore.class);
        var job = new AiDataRetentionJob(actionMapper,mockedStore,mock(DistributedGenerationAdmission.class));
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(job::runCleanup).isInstanceOf(IllegalStateException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verifyNoInteractions(actionMapper,mockedStore);
        } finally {
            Thread.interrupted();
        }
    }
}
