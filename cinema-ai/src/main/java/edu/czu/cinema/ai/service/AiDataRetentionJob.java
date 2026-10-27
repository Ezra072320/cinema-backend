package edu.czu.cinema.ai.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import edu.czu.cinema.ai.entity.AiAction;
import edu.czu.cinema.ai.mapper.AiActionMapper;
import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Shared cleanup operation; LOCAL and XXL choose exactly one trigger source. */
@Component @RequiredArgsConstructor @Slf4j
public class AiDataRetentionJob {
    private final AiActionMapper actionMapper;
    private final AiRetentionStore store;
    private final DistributedGenerationAdmission admission;
    static final int EXPIRED_GRACE_HOURS = 1;
    static final long SESSION_RETENTION_DAYS = 180;
    public record Result(int expired, int purged, int skipped, int failed) {}

    public Result runCleanup() {
        checkInterrupted();
        var now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        int expired = 0, skipped = 0, failed = 0;
        int[] purged = {0};
        try {
            expired = actionMapper.update(null,new LambdaUpdateWrapper<AiAction>()
                    .eq(AiAction::getStatus,"PENDING")
                    .lt(AiAction::getExpiresAt,now.minusHours(EXPIRED_GRACE_HOURS))
                    .set(AiAction::getStatus,"EXPIRED")
                    .last("ORDER BY expires_at,id LIMIT 200"));
        } catch (RuntimeException failure) {
            failed++;
            log.warn("AI action expiration failed type={}",failure.getClass().getSimpleName());
        }
        var threshold = now.minusDays(SESSION_RETENTION_DAYS);
        // A query outage must never be reported as successful cleanup.
        for (var candidate : store.candidates(threshold)) {
            checkInterrupted();
            try {
                boolean ran = admission.whileSessionIdle(candidate.userId(),candidate.chatId(),() -> {
                    if (store.purge(candidate,threshold)) purged[0]++;
                });
                if (!ran) skipped++;
            } catch (RuntimeException failure) {
                failed++;
                log.warn("AI session retention failed type={}",failure.getClass().getSimpleName());
            }
        }
        return new Result(expired,purged[0],skipped,failed);
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted())
            throw new IllegalStateException("AI retention interrupted; remaining sessions retained for retry");
    }

    public void cleanup() {
        var result = runCleanup();
        log.info("AI retention expired={} purged={} skipped={} failed={}",result.expired(),result.purged(),result.skipped(),result.failed());
        if (result.failed()>0) throw new IllegalStateException("AI cleanup contains failed items; retry required");
    }
}
