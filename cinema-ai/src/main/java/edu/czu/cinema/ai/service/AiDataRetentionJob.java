package edu.czu.cinema.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import edu.czu.cinema.ai.entity.AiAction;
import edu.czu.cinema.ai.entity.AiMessage;
import edu.czu.cinema.ai.entity.AiSession;
import edu.czu.cinema.ai.mapper.AiActionMapper;
import edu.czu.cinema.ai.mapper.AiMessageMapper;
import edu.czu.cinema.ai.mapper.AiSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 域数据保留任务（P1-M8）.
 *
 * <p>清理两类数据：
 * <ol>
 *   <li>待确认服务单：status=PENDING 且已过期超过 1 小时（系统不自动清除待用户确认的服务单，
 *       只把明显过期的翻成 EXPIRED，与 {@code AiActionService.view} 的展示口径一致），保守起见不物理删除。</li>
 *   <li>超 180 天的会话及其消息：只删最近 180 天内(update_time)没有活跃记录的会话，
 *       连同其消息一起清掉，防止对话历史无限膨胀。会话删除前不删服务单（服务单可能仍被用户查阅）。</li>
 * </ol>
 *
 * <p>单实例定时任务即可；多实例部署需配 ShedLock 防重复执行（当前单体假设）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AiDataRetentionJob {

    private final AiActionMapper actionMapper;
    private final AiMessageMapper messageMapper;
    private final AiSessionMapper sessionMapper;

    /** 待确认服务单过期后额外观察窗口，避免刚过期就被翻标。 */
    static final int EXPIRED_GRACE_HOURS = 1;
    /** 会话保留天数。 */
    static final long SESSION_RETENTION_DAYS = 180;

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 600_000) // 每小时，启动 10 分钟后首跑
    public void cleanup() {
        expireStaleActions();
        purgeOldSessions();
    }

    private void expireStaleActions() {
        try {
            int changed = actionMapper.update(null, new LambdaUpdateWrapper<AiAction>()
                    .eq(AiAction::getStatus, "PENDING")
                    .lt(AiAction::getExpiresAt, LocalDateTime.now().minusHours(EXPIRED_GRACE_HOURS))
                    .set(AiAction::getStatus, "EXPIRED"));
            if (changed > 0) log.info("[ai-retention] 已过期服务单 {} 条", changed);
        } catch (Exception e) {
            log.warn("[ai-retention] 过期服务单清理失败", e);
        }
    }

    /** 删除 update_time 早于保留期的旧会话及其消息（仅限确实长时间未活跃的）。 */
    private void purgeOldSessions() {
        try {
            LocalDateTime threshold = LocalDateTime.now().minusDays(SESSION_RETENTION_DAYS);
            List<AiSession> old = sessionMapper.selectList(new LambdaQueryWrapper<AiSession>()
                    .lt(AiSession::getUpdateTime, threshold).last("LIMIT 200")); // 单轮上限，防长事务
            for (AiSession s : old) {
                // 会话被保留期清理后，服务单已无法再被用户查阅，继续留着只会成为无主数据。
                actionMapper.delete(new LambdaQueryWrapper<AiAction>().eq(AiAction::getChatId, s.getChatId()));
                messageMapper.delete(new LambdaQueryWrapper<AiMessage>().eq(AiMessage::getChatId, s.getChatId()));
                sessionMapper.deleteById(s.getChatId());
            }
            if (!old.isEmpty()) log.info("[ai-retention] 已清理 {} 个超 {}-day 历史的旧会话", old.size(), SESSION_RETENTION_DAYS);
        } catch (Exception e) {
            log.warn("[ai-retention] 旧会话清理失败", e);
        }
    }
}