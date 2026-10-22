package edu.czu.cinema.ai.service;

import edu.czu.cinema.ai.entity.AiSession;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * AI 客服: 会话/消息持久化 + 流式对话.
 *
 * <p>所有按 chatId 的读写都做归属校验 —— chatId 虽是 UUID, 但不能当权限用.
 */
public interface AiChatService {

    /** 用户的全部会话, 最近活动在前. */
    List<AiSession> sessionsOf(Long userId);

    /**
     * 会话历史. 会话不存在返回空表; 存在但不属于自己 → FORBIDDEN.
     */
    List<AiSession.Message> history(Long userId, String chatId);

    /**
     * 流式对话. 会话不存在则创建(标题=提问截断), 用户提问同步落库,
     * 模型回复在流结束(含出错/中断)时落库.
     *
     * <p>文本元素为JSON字符串；error/done元素为JSON对象，done携带持久化后的服务单快照。
     */
    Flux<String> streamChat(Long userId, String chatId, String question);

    /** Stop this user's active generation, including when the browser has not received a token yet. */
    void stopChat(Long userId, String chatId);

    /** 删除会话及其全部消息. 会话不存在为空操作; 不属于自己 → FORBIDDEN. */
    void deleteSession(Long userId, String chatId);
}
