package edu.czu.cinema.ai.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisMapperBuilderAssistant;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.czu.cinema.ai.entity.AiAction;
import edu.czu.cinema.ai.entity.AiMessage;
import edu.czu.cinema.ai.entity.AiSession;
import edu.czu.cinema.ai.mapper.AiMessageMapper;
import edu.czu.cinema.ai.mapper.AiSessionMapper;
import edu.czu.cinema.common.api.ResultCode;
import edu.czu.cinema.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * AI 客服持久化实现的回归测试(2026-10-19 重写).
 *
 * <p>回归背景: 旧内存实现 ① chatId 裸 key 无归属校验, 谁拿到 id 都能读历史;
 * ② 会话/消息重启即失; ③ ts 是"现在往前每条减 1 秒"伪造的.
 * 本测试锁住归属校验、真实时间戳、标题截断与回复落库口径.
 */
class PersistentAiChatServiceTest {

    private final AiSessionMapper sessionMapper = mock(AiSessionMapper.class);
    private final AiMessageMapper messageMapper = mock(AiMessageMapper.class);

    /** 纯单测没有 Spring 容器，Lambda 列名解析需要先注册 TableInfo. */
    @BeforeAll
    static void initTableInfo() {
        var assistant = new MybatisMapperBuilderAssistant(new MybatisConfiguration(), "ai-test");
        TableInfoHelper.initTableInfo(assistant, AiAction.class);
        TableInfoHelper.initTableInfo(assistant, AiMessage.class);
        TableInfoHelper.initTableInfo(assistant, AiSession.class);
    }

    private PersistentAiChatService service() {
        return new PersistentAiChatService(
                mock(ChatClient.class), sessionMapper, messageMapper, new ObjectMapper(),
                mock(edu.czu.cinema.ai.service.AiServiceGuide.class),
                mock(edu.czu.cinema.ai.mapper.AiActionMapper.class));
    }

    private static AiSession session(Long userId) {
        AiSession s = new AiSession();
        s.setChatId("cid-1");
        s.setUserId(userId);
        s.setTitle("标题");
        return s;
    }

    // ---------- history ----------

    @Test
    void historyRejectsForeignSession() {
        when(sessionMapper.selectById("cid-1")).thenReturn(session(2L)); // 别人的会话

        assertThatThrownBy(() -> service().history(1L, "cid-1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(ResultCode.FORBIDDEN.getCode()));
    }

    @Test
    void historyEmptyWhenSessionMissing() {
        when(sessionMapper.selectById("nope")).thenReturn(null);

        assertThat(service().history(1L, "nope")).isEmpty();
        verifyNoInteractions(messageMapper);
    }

    @Test
    void historyMapsRealTimestamps() {
        when(sessionMapper.selectById("cid-1")).thenReturn(session(1L));
        LocalDateTime t = LocalDateTime.of(2026, 10, 19, 12, 0, 0);
        AiMessage user = new AiMessage();
        user.setRole("user");
        user.setContent("在吗");
        user.setCreateTime(t);
        AiMessage bot = new AiMessage();
        bot.setRole("assistant");
        bot.setContent("在的");
        bot.setCreateTime(t.plusSeconds(3));
        when(messageMapper.selectList(any())).thenReturn(List.of(user, bot));

        List<AiSession.Message> history = service().history(1L, "cid-1");

        assertThat(history).hasSize(2);
        long expected = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        assertThat(history.get(0).getTs()).isEqualTo(expected);
        assertThat(history.get(1).getTs()).isEqualTo(expected + 3000);
        assertThat(history.get(0).getRole()).isEqualTo("user");
        assertThat(history.get(1).getContent()).isEqualTo("在的");
    }

    // ---------- deleteSession ----------

    @Test
    void deleteRejectsForeignSession() {
        when(sessionMapper.selectById("cid-1")).thenReturn(session(2L));

        assertThatThrownBy(() -> service().deleteSession(1L, "cid-1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(ResultCode.FORBIDDEN.getCode()));
        verify(messageMapper, never()).delete(any());
        verify(sessionMapper, never()).deleteById(any(java.io.Serializable.class));
    }

    @Test
    void deleteRemovesSessionAndMessages() {
        when(sessionMapper.selectById("cid-1")).thenReturn(session(1L));

        service().deleteSession(1L, "cid-1");

        verify(messageMapper).delete(any());
        verify(sessionMapper).deleteById("cid-1");
    }

    @Test
    void deleteMissingSessionIsNoop() {
        when(sessionMapper.selectById("nope")).thenReturn(null);

        service().deleteSession(1L, "nope");

        verifyNoInteractions(messageMapper);
        verify(sessionMapper, never()).deleteById(any(java.io.Serializable.class));
    }

    // ---------- ensureSession / persistReply ----------

    @Test
    void firstQuestionCreatesSessionWithTitle() {
        when(sessionMapper.selectById("cid-1")).thenReturn(null);

        AiSession created = service().ensureSession(1L, "cid-1", "  你好  请问\n今天有什么电影  ");

        ArgumentCaptor<AiSession> cap = ArgumentCaptor.forClass(AiSession.class);
        verify(sessionMapper).insert(cap.capture());
        assertThat(cap.getValue().getUserId()).isEqualTo(1L);
        // 标题 = 提问压成单行(空白折叠); 未超长不截断
        assertThat(created.getTitle()).isEqualTo("你好 请问 今天有什么电影");
    }

    @Test
    void ensureSessionRejectsForeignChatId() {
        when(sessionMapper.selectById("cid-1")).thenReturn(session(2L));

        assertThatThrownBy(() -> service().ensureSession(1L, "cid-1", "hi"))
                .isInstanceOf(BusinessException.class);
        verify(sessionMapper, never()).insert(any(AiSession.class));
    }

    @Test
    void persistReplySavesAssistantAndSnippet() {
        AiSession session = session(1L);
        service().persistReply(1L, "cid-1", session, "问题", "  回复内容  ");

        ArgumentCaptor<AiMessage> cap = ArgumentCaptor.forClass(AiMessage.class);
        verify(messageMapper).insert(cap.capture());
        assertThat(cap.getValue().getRole()).isEqualTo("assistant");
        assertThat(cap.getValue().getContent()).isEqualTo("回复内容");
        assertThat(session.getLastMessage()).isEqualTo("回复内容");
        assertThat(session.getUpdateTime()).isNotNull();
    }

    @Test
    void persistReplyFallsBackToQuestionSnippetWhenModelSilent() {
        AiSession session = session(1L);
        service().persistReply(1L, "cid-1", session, "提问内容", "   ");

        verify(messageMapper, never()).insert(any(AiMessage.class));
        assertThat(session.getLastMessage()).isEqualTo("提问内容");
    }

    // ---------- 小工具 ----------

    @Test
    void titleOfCollapsesWhitespaceAndTruncates() {
        assertThat(PersistentAiChatService.titleOf("a\n\n  b\t c")).isEqualTo("a b c");
        String longQ = "一".repeat(40);
        assertThat(PersistentAiChatService.titleOf(longQ)).hasSize(PersistentAiChatService.TITLE_MAX);
    }

    @Test
    void encodeTokenEscapesNewlinesAsJson() {
        assertThat(service().encodeToken("你\n好\"x"))
                .isEqualTo("\"你\\n好\\\"x\"");
    }

    @Test
    void streamingInjectsTrustedIdentityAndPersistsReplyBeforeCompletion() {
        ChatClient client = mock(ChatClient.class);
        var request = mock(ChatClient.ChatClientRequestSpec.class, RETURNS_SELF);
        var stream = mock(ChatClient.StreamResponseSpec.class);
        when(client.prompt()).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        when(stream.content()).thenReturn(reactor.core.publisher.Flux.just("你\n好"));
        when(sessionMapper.selectById("cid-1")).thenReturn(session(1L));
        when(messageMapper.selectList(any())).thenReturn(List.of());
        var guide = mock(edu.czu.cinema.ai.service.AiServiceGuide.class);
        when(guide.content()).thenReturn("业务指南");
        var service = new PersistentAiChatService(client, sessionMapper, messageMapper, new ObjectMapper(),
                guide, mock(edu.czu.cinema.ai.mapper.AiActionMapper.class));
        assertThat(service.streamChat(1L, "cid-1", "你好").collectList().block()).containsExactly("\"你\\n好\"");
        @SuppressWarnings("unchecked") ArgumentCaptor<java.util.Map<String,Object>> cap = ArgumentCaptor.forClass(java.util.Map.class);
        verify(request).toolContext(cap.capture());
        var identity = (edu.czu.cinema.ai.service.AiIdentity)cap.getValue().get(edu.czu.cinema.ai.service.AiIdentity.CONTEXT_KEY);
        assertThat(identity.userId()).isEqualTo(1L);
        assertThat(identity.chatId()).isEqualTo("cid-1");
        ArgumentCaptor<AiMessage> messages = ArgumentCaptor.forClass(AiMessage.class);
        verify(messageMapper, times(2)).insert(messages.capture());
        assertThat(messages.getAllValues().get(1).getContent()).isEqualTo("你\n好");
    }

    @Test
    void foreignStreamCannotReadAnyHistory() {
        when(sessionMapper.selectById("cid-1")).thenReturn(session(2L));
        assertThatThrownBy(() -> service().streamChat(1L, "cid-1", "你好")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(messageMapper);
    }

    // ---------- M2 用户级限流 / running TTL ----------

    @Test
    void perUserConcurrentGenerationLimitIsEnforced() {
        when(sessionMapper.selectById("cid-1")).thenReturn(session(1L));
        var service = service();
        for (int i = 0; i < PersistentAiChatService.MAX_CONCURRENT_PER_USER; i++) {
            assertThat(service.tryAcquire(1L)).isTrue();
        }
        assertThat(service.tryAcquire(1L)).isFalse();
        service.release(1L);
        assertThat(service.tryAcquire(1L)).isTrue();
    }

    @Test
    void runningLockExpiresAfterTtlAndCanBeOverwritten() {
        when(sessionMapper.selectById("cid-1")).thenReturn(session(1L));
        var service = service();
        service.runningTtl = java.time.Duration.ofMillis(50);
        // 可控时钟：同一毫秒内的连续调用也能精确验证 TTL 边界，避免 sleep 竞态
        AtomicLong clock = new AtomicLong(1_000_000L);
        service.timeSource = clock::get;

        assertThat(service.tryStartGeneration("cid-1")).isTrue();
        assertThat(service.tryStartGeneration("cid-1")).isFalse(); // 未过期：冲突
        assertThat(service.isGenerating("cid-1")).isTrue();

        // 超过 TTL：过期锁可被覆盖（含 Flux 未订阅/Error 泄漏未释放的场景）。
        clock.addAndGet(51);
        assertThat(service.isGenerating("cid-1")).isFalse();
        assertThat(service.tryStartGeneration("cid-1")).isTrue();
        service.releaseGeneration(1L, "cid-1");
    }

    @Test
    void ensureSessionRejectsWhenSessionCountAtLimit() {
        when(sessionMapper.selectById("nope")).thenReturn(null);
        when(sessionMapper.selectCount(any())).thenReturn((long) PersistentAiChatService.MAX_SESSIONS_PER_USER);
        assertThatThrownBy(() -> service().ensureSession(1L, "nope", "hi"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("上限");
        verify(sessionMapper, never()).insert(any(AiSession.class));
    }

    @Test
    void truncateByCharsDropsOldestUntilBudgetMet() {
        java.util.ArrayList<AiMessage> rows = new java.util.ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            AiMessage m = new AiMessage(); m.setRole("user"); m.setContent("x".repeat(100)); rows.add(m);
        }
        var kept = PersistentAiChatService.truncateByChars(rows, 250);
        assertThat(kept).hasSize(2); // 4*100=400>250 → 丢最旧 2 条
        assertThat(kept.get(0).getContent()).isEqualTo("x".repeat(100));
    }

    @Test
    void truncateByCharsKeepsAllWhenWithinBudget() {
        java.util.ArrayList<AiMessage> rows = new java.util.ArrayList<>();
        AiMessage m = new AiMessage(); m.setContent("短"); rows.add(m);
        assertThat(PersistentAiChatService.truncateByChars(rows, 1000)).hasSize(1);
    }

    // ---------- M8 数据保留任务 ----------

    @Test
    void retentionJobExpiresStalePendingActions() {
        var actionMapper = mock(edu.czu.cinema.ai.mapper.AiActionMapper.class);
        when(messageMapper.delete(any())).thenReturn(1);
        when(sessionMapper.deleteById(any())).thenReturn(1);
        when(actionMapper.update(any(), any())).thenReturn(3);
        var job = new edu.czu.cinema.ai.service.AiDataRetentionJob(actionMapper, messageMapper, sessionMapper);
        job.cleanup();
        verify(actionMapper).update(eq(null), any());
    }

    @Test
    void retentionJobPurgesOldInactiveSessionsWithTheirMessages() {
        var actionMapper = mock(edu.czu.cinema.ai.mapper.AiActionMapper.class);
        AiSession old = session(1L);
        old.setUpdateTime(java.time.LocalDateTime.now().minusDays(400)); // 远超 180 天保留期
        when(sessionMapper.selectList(any())).thenReturn(java.util.List.of(old));
        when(sessionMapper.deleteById(any())).thenReturn(1);
        when(messageMapper.delete(any())).thenReturn(1);
        var job = new edu.czu.cinema.ai.service.AiDataRetentionJob(actionMapper, messageMapper, sessionMapper);
        job.cleanup();
        verify(messageMapper).delete(any());
        verify(sessionMapper).deleteById("cid-1");
    }
}
