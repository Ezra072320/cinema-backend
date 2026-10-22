package edu.czu.cinema.ai.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisMapperBuilderAssistant;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.czu.cinema.ai.entity.AiAction;
import edu.czu.cinema.ai.entity.AiMessage;
import edu.czu.cinema.ai.entity.AiSession;
import edu.czu.cinema.ai.mapper.AiActionMapper;
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

    private static AiMessage msg(String role, String content) {
        var message = new AiMessage();
        message.setRole(role);
        message.setContent(content);
        message.setCreateTime(LocalDateTime.now());
        return message;
    }

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
                mock(edu.czu.cinema.ai.mapper.AiActionMapper.class),
                mock(edu.czu.cinema.ai.service.AiActionService.class),
                mock(edu.czu.cinema.ai.service.AiSeatSelectionStore.class));
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

    // ---------- 多轮服务单上下文 ----------

    @Test
    void actionContextCarriesOrderIdAndServiceSlipStatusForNextTurn() {
        AiActionMapper actionMapper = mock(AiActionMapper.class);
        AiAction create = new AiAction();
        create.setType("CREATE_ORDER");
        create.setStatus("DONE");
        create.setPath("/pay/4");
        create.setTitle("确认购票");
        AiAction refund = new AiAction();
        refund.setType("REFUND_ORDER");
        refund.setStatus("PENDING");
        refund.setPath("/order/4");
        refund.setTitle("确认申请退票");
        when(actionMapper.selectList(any())).thenReturn(List.of(create, refund));
        var service =
                new PersistentAiChatService(
                        mock(ChatClient.class),
                        sessionMapper,
                        messageMapper,
                        new ObjectMapper(),
                        mock(edu.czu.cinema.ai.service.AiServiceGuide.class),
                        actionMapper,
                        mock(edu.czu.cinema.ai.service.AiActionService.class),
                        mock(edu.czu.cinema.ai.service.AiSeatSelectionStore.class));

        String context = service.actionContext(1L, "cid-1");

        assertThat(context)
                .contains("CREATE_ORDER", "REFUND_ORDER", "orderId=4", "状态=DONE", "状态=PENDING");
    }

    @Test
    void afterSaleIntentIsDetectedButRulesQuestionsAreNot() {
        assertThat(PersistentAiChatService.isAfterSaleRequest("我已经支付了，帮我退款。")).isTrue();
        assertThat(PersistentAiChatService.isAfterSaleRequest("能帮我把这个订单退款吗")).isTrue();
        assertThat(PersistentAiChatService.isAfterSaleRequest("帮我退款，能退多少")).isTrue();
        assertThat(PersistentAiChatService.isAfterSaleRequest("退款规则是什么")).isFalse();
        assertThat(PersistentAiChatService.isAfterSaleRequest("我要取消想看")).isFalse();
        assertThat(PersistentAiChatService.isAfterSaleRequest("帮我取消这个服务单")).isFalse();
        assertThat(PersistentAiChatService.extractOrderIdFromQuestion("订单5怎么退")).isEqualTo(5L);
        assertThat(PersistentAiChatService.extractOrderIdFromQuestion("我要退款")).isNull();
    }

    @Test
    void autoAfterSaleCreatesSlipForTheOnlyOrderInTheChat() {
        AiActionMapper actionMapper = mock(AiActionMapper.class);
        AiAction create = new AiAction();
        create.setType("CREATE_ORDER");
        create.setStatus("DONE");
        create.setPath("/pay/5");
        create.setTitle("确认购票");
        when(actionMapper.selectList(any())).thenReturn(List.of(create));
        var actions = mock(edu.czu.cinema.ai.service.AiActionService.class);
        var order = new edu.czu.cinema.domain.order.vo.OrderVO();
        order.setId(5L);
        order.setNumber("202610211624220002801916");
        order.setStatus(2);
        var view =
                new edu.czu.cinema.ai.service.AiActionService.View(
                        "a",
                        "REFUND_ORDER",
                        "确认申请退票",
                        "退票 ¥120.00",
                        "PENDING",
                        null,
                        null,
                        "/order/5");
        when(actions.prepareAfterSale(
                        any(edu.czu.cinema.ai.service.AiIdentity.class), eq(5L)))
                .thenReturn(
                        new edu.czu.cinema.ai.service.AiActionService.AfterSaleResult(
                                order, view, "申请退票"));
        var service =
                new PersistentAiChatService(
                        mock(ChatClient.class),
                        sessionMapper,
                        messageMapper,
                        new ObjectMapper(),
                        mock(edu.czu.cinema.ai.service.AiServiceGuide.class),
                        actionMapper,
                        actions,
                        mock(edu.czu.cinema.ai.service.AiSeatSelectionStore.class));

        String note = service.autoPrepareAfterSale(1L, "cid-1", "我已经支付了，帮我退款。");

        assertThat(note).contains("订单ID 5", "申请退票", "预生成");
        verify(actions)
                .prepareAfterSale(
                        any(edu.czu.cinema.ai.service.AiIdentity.class), eq(5L));
    }

    @Test
    void purchaseClaimIsOnlyRecognizedForTicketSlips() {
        assertThat(PersistentAiChatService.claimsGeneratedPurchase("已为您生成购票服务单")).isTrue();
        assertThat(PersistentAiChatService.claimsGeneratedPurchase("已为您生成退票服务单")).isFalse();
        assertThat(PersistentAiChatService.claimsGeneratedPurchase("请点击下方服务单确认")).isFalse();
    }

    @Test
    void purchaseClaimWithoutToolCallIsCompletedFromVerifiedSeat() {
        var store = mock(edu.czu.cinema.ai.service.AiSeatSelectionStore.class);
        when(store.recent("cid-1"))
                .thenReturn(
                        new edu.czu.cinema.ai.service.AiSeatSelectionStore.Selection(
                                1L, List.of(5L), "2-2", 0L));
        var actions = mock(edu.czu.cinema.ai.service.AiActionService.class);
        var service =
                new PersistentAiChatService(
                        mock(ChatClient.class),
                        sessionMapper,
                        messageMapper,
                        new ObjectMapper(),
                        mock(edu.czu.cinema.ai.service.AiServiceGuide.class),
                        mock(AiActionMapper.class),
                        actions,
                        store);

        service.autoCompletePurchaseClaim(1L, "cid-1", "帮我买2排2座", "已为您生成购票服务单，请在下方确认。");

        var captor = ArgumentCaptor.forClass(edu.czu.cinema.ai.dto.AiActionCommand.class);
        verify(actions).prepare(any(edu.czu.cinema.ai.service.AiIdentity.class), captor.capture());
        assertThat(captor.getValue().type())
                .isEqualTo(edu.czu.cinema.ai.dto.AiActionCommand.Type.CREATE_ORDER);
        assertThat(captor.getValue().scheduleId()).isEqualTo(1L);
        assertThat(captor.getValue().ticketIds()).containsExactly(5L);
    }

    @Test
    void refundClaimDoesNotCreatePurchaseSlip() {
        var store = mock(edu.czu.cinema.ai.service.AiSeatSelectionStore.class);
        var actions = mock(edu.czu.cinema.ai.service.AiActionService.class);
        var service =
                new PersistentAiChatService(
                        mock(ChatClient.class),
                        sessionMapper,
                        messageMapper,
                        new ObjectMapper(),
                        mock(edu.czu.cinema.ai.service.AiServiceGuide.class),
                        mock(AiActionMapper.class),
                        actions,
                        store);

        service.autoCompletePurchaseClaim(1L, "cid-1", "帮我退款", "已为您生成退票服务单，请在下方确认。");

        verifyNoInteractions(actions);
    }

    @Test
    void recoveryRequestCreatesSlipFromTheLastVerifiedSeat() {
        var store = mock(edu.czu.cinema.ai.service.AiSeatSelectionStore.class);
        when(store.recent("cid-1"))
                .thenReturn(
                        new edu.czu.cinema.ai.service.AiSeatSelectionStore.Selection(
                                1L, List.of(5L), "2-2", 0L));
        AiMessage last = new AiMessage();
        last.setRole("assistant");
        last.setContent("已为您生成购票服务单，请在下方确认。");
        when(messageMapper.selectList(any())).thenReturn(List.of(last));
        var actions = mock(edu.czu.cinema.ai.service.AiActionService.class);
        var service =
                new PersistentAiChatService(
                        mock(ChatClient.class),
                        sessionMapper,
                        messageMapper,
                        new ObjectMapper(),
                        mock(edu.czu.cinema.ai.service.AiServiceGuide.class),
                        mock(AiActionMapper.class),
                        actions,
                        store);

        String note = service.autoPreparePurchase(1L, "cid-1", "我没看见");

        assertThat(note).contains("预生成");
        verify(actions)
                .prepare(
                        any(edu.czu.cinema.ai.service.AiIdentity.class),
                        any(edu.czu.cinema.ai.dto.AiActionCommand.class));
    }

    @Test
    void autoAfterSaleDoesNotRefundAnOlderOrderWhenLatestSlipIsPendingCreate() {
        AiActionMapper actionMapper = mock(AiActionMapper.class);
        AiAction pending = new AiAction();
        pending.setType("CREATE_ORDER");
        pending.setStatus("PENDING");
        pending.setPath("/seats/1");
        pending.setTitle("确认购票");
        AiAction older = new AiAction();
        older.setType("CREATE_ORDER");
        older.setStatus("DONE");
        older.setPath("/pay/5");
        older.setTitle("确认购票");
        when(actionMapper.selectList(any())).thenReturn(List.of(pending, older));
        var actions = mock(edu.czu.cinema.ai.service.AiActionService.class);
        var service =
                new PersistentAiChatService(
                        mock(ChatClient.class),
                        sessionMapper,
                        messageMapper,
                        new ObjectMapper(),
                        mock(edu.czu.cinema.ai.service.AiServiceGuide.class),
                        actionMapper,
                        actions,
                        mock(edu.czu.cinema.ai.service.AiSeatSelectionStore.class));

        String note = service.autoPrepareAfterSale(1L, "cid-1", "帮我退款");

        assertThat(note).contains("撤销");
        verifyNoInteractions(actions);
    }

    @Test
    void extractOrderIdOnlyFromOrderPaths() {
        assertThat(PersistentAiChatService.extractOrderId("/pay/4")).isEqualTo(4L);
        assertThat(PersistentAiChatService.extractOrderId("/order/12")).isEqualTo(12L);
        assertThat(PersistentAiChatService.extractOrderId("/score/3/10")).isNull();
        assertThat(PersistentAiChatService.extractOrderId("/seats/1")).isNull();
        assertThat(PersistentAiChatService.extractOrderId(null)).isNull();
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
                guide, mock(edu.czu.cinema.ai.mapper.AiActionMapper.class),
                mock(edu.czu.cinema.ai.service.AiActionService.class),
                mock(edu.czu.cinema.ai.service.AiSeatSelectionStore.class));
        var events = service.streamChat(1L, "cid-1", "你好").doOnNext(event -> {
            if (event.contains("\"type\":\"done\"")) {
                verify(messageMapper, times(2)).insert(any(AiMessage.class));
                assertThat(service.isGenerating("cid-1")).isFalse();
            }
        }).collectList().block();
        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isEqualTo("\"你\\n好\"");
        assertThat(events.get(1)).contains("\"type\":\"done\"", "\"actions\":[]", "\"completed\":true");
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

    @Test
    void explicitDifferentOrderMustNeverFallBackToTheOldOrder() {
        var mapper = mock(AiActionMapper.class);
        var old = new AiAction();
        old.setType("CREATE_ORDER");
        old.setStatus("DONE");
        old.setPath("/pay/5");
        when(mapper.selectList(any())).thenReturn(List.of(old));
        var actions = mock(edu.czu.cinema.ai.service.AiActionService.class);
        when(actions.prepareAfterSale(any(), eq(9L))).thenThrow(new BusinessException("订单不存在"));
        var service = new PersistentAiChatService(mock(ChatClient.class), sessionMapper, messageMapper,
                new ObjectMapper(), mock(edu.czu.cinema.ai.service.AiServiceGuide.class), mapper,
                actions, mock(edu.czu.cinema.ai.service.AiSeatSelectionStore.class));

        assertThat(service.autoPrepareAfterSale(1L, "cid-1", "帮我退款，订单ID为9")).contains("订单不存在");
        verify(actions).prepareAfterSale(any(), eq(9L));
        verify(actions, never()).prepareAfterSale(any(), eq(5L));
    }

    @Test
    void explicitReviewUsesNewOrderAndPreservesExactUserComment() {
        var actions = mock(edu.czu.cinema.ai.service.AiActionService.class);
        var service = new PersistentAiChatService(mock(ChatClient.class), sessionMapper, messageMapper,
                new ObjectMapper(), mock(edu.czu.cinema.ai.service.AiServiceGuide.class), mock(AiActionMapper.class),
                actions, mock(edu.czu.cinema.ai.service.AiSeatSelectionStore.class));
        assertThat(service.autoPrepareReview(1L, "cid-1", "为订单ID为16提交5分观影评价，评论原文是“节奏不错。”，请生成评价服务单。"))
                .contains("订单ID 16", "预生成");
        var command = ArgumentCaptor.forClass(edu.czu.cinema.ai.dto.AiActionCommand.class);
        verify(actions).prepare(any(), command.capture());
        assertThat(command.getValue().orderId()).isEqualTo(16L);
        assertThat(command.getValue().score()).isEqualTo(5);
        assertThat(command.getValue().content()).isEqualTo("节奏不错。");
        assertThat(command.getValue().type()).isEqualTo(edu.czu.cinema.ai.dto.AiActionCommand.Type.SUBMIT_SCORE);
    }

    @Test
    void explicitSeatPurchasePreparesARealSlipEvenAfterAnOlderSlipWasDismissed() {
        var actions = mock(edu.czu.cinema.ai.service.AiActionService.class);
        var service = new PersistentAiChatService(mock(ChatClient.class), sessionMapper, messageMapper,
                new ObjectMapper(), mock(edu.czu.cinema.ai.service.AiServiceGuide.class), mock(AiActionMapper.class),
                actions, mock(edu.czu.cinema.ai.service.AiSeatSelectionStore.class));
        assertThat(service.autoPrepareBooking(1L, "cid-1", "帮我买场次ID为31的1排2座，生成新的购票服务单。"))
                .contains("场次ID 31", "1-2", "预生成");
        verify(actions).prepareBookingBySeat(any(), eq(31L), eq("1-2"));
        clearInvocations(actions);
        for (String question : List.of("只查询场次ID为31的1排2座，不要买票", "帮我买1排2座", "帮我买场次31的1排1座和1排2座"))
            assertThat(service.autoPrepareBooking(1L, "cid-1", question)).isEqualTo("未触发自动拟单。");
        verifyNoInteractions(actions);
    }

    @Test
    void incompleteAmbiguousOrNegatedReviewInstructionsDoNotAutoPrepare() {
        var actions = mock(edu.czu.cinema.ai.service.AiActionService.class);
        var service = new PersistentAiChatService(mock(ChatClient.class), sessionMapper, messageMapper,
                new ObjectMapper(), mock(edu.czu.cinema.ai.service.AiServiceGuide.class), mock(AiActionMapper.class),
                actions, mock(edu.czu.cinema.ai.service.AiSeatSelectionStore.class));
        for (String question : List.of("不要为订单16提交5分评价，评论原文是“不错”", "为订单16提交5分评价",
                "为订单16提交5分还是4分的评价，评论原文是“不错”", "为订单16和订单17提交5分评价，评论原文是“不错”",
                "我要知道评价怎么提交", "为订单号202610211624220002801916提交5分评价")) {
            assertThat(service.autoPrepareReview(1L, "cid-1", question)).isEqualTo("未触发自动拟单。");
        }
        verifyNoInteractions(actions);
    }

    @Test
    void afterSaleConsultationOrNegationMustNotCreateSlips() {
        assertThat(PersistentAiChatService.isAfterSaleRequest("不要帮我退款，我只想查支付状态")).isFalse();
        assertThat(PersistentAiChatService.isAfterSaleRequest("我不想取消订单")).isFalse();
        assertThat(PersistentAiChatService.extractOrderIdFromQuestion("订单号202610211624220002801916帮我退款")).isNull();
        assertThat(PersistentAiChatService.extractOrderIdFromQuestion("订单ID为9，帮我退票")).isEqualTo(9L);
        assertThat(PersistentAiChatService.extractOrderIdFromQuestion("帮我退掉/order/123")).isEqualTo(123L);
    }

    @Test
    void queryingSeatsDoesNotAuthorizeAPurchaseEvenWhenTheModelClaimsOne() {
        var store = mock(edu.czu.cinema.ai.service.AiSeatSelectionStore.class);
        when(store.recent("cid-1")).thenReturn(new edu.czu.cinema.ai.service.AiSeatSelectionStore.Selection(
                1L, List.of(5L), "2-2", System.currentTimeMillis()));
        var actions = mock(edu.czu.cinema.ai.service.AiActionService.class);
        var service = new PersistentAiChatService(mock(ChatClient.class), sessionMapper, messageMapper,
                new ObjectMapper(), mock(edu.czu.cinema.ai.service.AiServiceGuide.class), mock(AiActionMapper.class),
                actions, store);
        service.autoCompletePurchaseClaim(1L, "cid-1", "查一下2排2座，不要买票", "已为您生成购票服务单");
        verifyNoInteractions(actions);
    }

    @Test
    void toolReferencesSurviveTheNextTurnButStayOutOfVisibleHistory() {
        var reference = msg("tool_context", "[{\"tool\":\"seatDetails\",\"result\":\"scheduleId=31,ticketId=8\"}]");
        when(sessionMapper.selectById("cid-1")).thenReturn(session(1L));
        when(messageMapper.selectList(any())).thenReturn(List.of(reference, msg("assistant", "该座位可售")));
        assertThat(service().history(1L, "cid-1")).singleElement()
                .satisfies(message -> assertThat(message.getRole()).isEqualTo("assistant"));

        var client = mock(ChatClient.class);
        var request = mock(ChatClient.ChatClientRequestSpec.class, RETURNS_SELF);
        var stream = mock(ChatClient.StreamResponseSpec.class);
        when(client.prompt()).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        when(stream.content()).thenReturn(reactor.core.publisher.Flux.just("请核对服务单"));
        var guide = mock(edu.czu.cinema.ai.service.AiServiceGuide.class);
        when(guide.content()).thenReturn("指南");
        var service = new PersistentAiChatService(client, sessionMapper, messageMapper, new ObjectMapper(),
                guide, mock(AiActionMapper.class), mock(edu.czu.cinema.ai.service.AiActionService.class),
                mock(edu.czu.cinema.ai.service.AiSeatSelectionStore.class));
        service.streamChat(1L, "cid-1", "就买刚才那个座位").collectList().block();
        verify(request).system(contains("scheduleId=31,ticketId=8"));
    }

    @Test
    void modelFailureStillEmitsActualSlipsAndReleasesConversation() {
        var client = mock(ChatClient.class);
        var request = mock(ChatClient.ChatClientRequestSpec.class, RETURNS_SELF);
        var stream = mock(ChatClient.StreamResponseSpec.class);
        when(client.prompt()).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        when(stream.content()).thenReturn(reactor.core.publisher.Flux.error(new IllegalStateException("upstream down")));
        when(sessionMapper.selectById("cid-1")).thenReturn(session(1L));
        var guide = mock(edu.czu.cinema.ai.service.AiServiceGuide.class);
        when(guide.content()).thenReturn("指南");
        var actions = mock(edu.czu.cinema.ai.service.AiActionService.class);
        when(actions.list(1L, "cid-1")).thenReturn(List.of(new edu.czu.cinema.ai.service.AiActionService.View(
                "real-slip", "SET_WANT", "想看", "影片", "PENDING", null, null, "/me/wants")));
        var service = new PersistentAiChatService(client, sessionMapper, messageMapper, new ObjectMapper(),
                guide, mock(AiActionMapper.class), actions, mock(edu.czu.cinema.ai.service.AiSeatSelectionStore.class));
        var events = service.streamChat(1L, "cid-1", "你好").collectList().block();
        assertThat(events).hasSize(2);
        assertThat(events.getFirst()).contains("\"type\":\"error\"").doesNotContain("upstream down");
        assertThat(events.getLast()).contains("\"completed\":false", "real-slip");
        assertThat(service.isGenerating("cid-1")).isFalse();
    }

    @Test
    void explicitStopBeforeFirstTokenReleasesLockAndAllowsTheNextTurn() {
        var client = mock(ChatClient.class);
        var request = mock(ChatClient.ChatClientRequestSpec.class, RETURNS_SELF);
        var stream = mock(ChatClient.StreamResponseSpec.class);
        when(client.prompt()).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        when(stream.content()).thenReturn(reactor.core.publisher.Flux.never());
        when(sessionMapper.selectById("cid-1")).thenReturn(session(1L));
        var guide = mock(edu.czu.cinema.ai.service.AiServiceGuide.class);
        when(guide.content()).thenReturn("指南");
        var service = new PersistentAiChatService(client, sessionMapper, messageMapper, new ObjectMapper(),
                guide, mock(AiActionMapper.class), mock(edu.czu.cinema.ai.service.AiActionService.class),
                mock(edu.czu.cinema.ai.service.AiSeatSelectionStore.class));
        var events = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var subscription = service.streamChat(1L, "cid-1", "你好").subscribe(events::add);
        assertThat(service.isGenerating("cid-1")).isTrue();
        assertThatThrownBy(() -> service.stopChat(2L, "cid-1")).isInstanceOf(BusinessException.class);
        assertThat(service.isGenerating("cid-1")).isTrue();
        service.stopChat(1L, "cid-1");
        assertThat(events).singleElement().satisfies(event -> assertThat(event).contains("\"completed\":false"));
        assertThat(service.isGenerating("cid-1")).isFalse();
        var persisted = ArgumentCaptor.forClass(AiMessage.class);
        verify(messageMapper, times(2)).insert(persisted.capture());
        assertThat(persisted.getAllValues().getLast().getRole()).isEqualTo("assistant");
        assertThat(persisted.getAllValues().getLast().getContent()).contains("本轮回复已停止或未完成");
        var next = service.streamChat(1L, "cid-1", "继续").subscribe();
        service.stopChat(1L, "cid-1");
        next.dispose();
        subscription.dispose();
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
