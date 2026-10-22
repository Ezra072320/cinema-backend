package edu.czu.cinema.ai.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本会话最近一次“已确认可售”的具体座位。
 *
 * <p>模型在多轮里经常只描述座位、声称已生成购票服务单，却没有真正调用
 * {@code prepareBookingBySeat}。记录 seatDetails/availableSeats(seatLabel) 返回的
 * 可售 ticketId 后，服务端可以在模型“只说不做”时按这个已验证的座位确定性补单。
 *
 * <p>单实例内存存储：座位查询、模型回复和补单发生在同一次流式请求的同一实例内；
 * 多副本部署若需要跨请求恢复，应改为 Redis（与本项目 AI 限流的单实例假设一致）。
 */
@Component
public class AiSeatSelectionStore {

    public record Selection(Long scheduleId, List<Long> ticketIds, String seatLabel, long at) {}

    private static final long TTL_MS = 10 * 60_000L;

    private final Map<String, Selection> byChat = new ConcurrentHashMap<>();

    public void remember(String chatId, Long scheduleId, List<Long> ticketIds, String seatLabel) {
        if (chatId == null || scheduleId == null || ticketIds == null || ticketIds.isEmpty()) return;
        long now = System.currentTimeMillis();
        byChat.entrySet().removeIf(e -> now - e.getValue().at() > TTL_MS);
        byChat.put(chatId, new Selection(scheduleId, List.copyOf(ticketIds), seatLabel, now));
    }

    public Selection recent(String chatId) {
        if (chatId == null) return null;
        Selection selection = byChat.get(chatId);
        if (selection == null) return null;
        if (System.currentTimeMillis() - selection.at() > TTL_MS) {
            byChat.remove(chatId);
            return null;
        }
        return selection;
    }

    public void clear(String chatId) {
        if (chatId != null) byChat.remove(chatId);
    }
}
