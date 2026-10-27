package edu.czu.cinema.jobs;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import edu.czu.cinema.ai.service.AiDataRetentionJob;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
@ConditionalOnProperty(name="cinema.jobs.mode",havingValue="xxl")
public class AiJobHandlers {
    private final JobMutex mutex;
    private final AiDataRetentionJob job;
    @XxlJob("aiHistoryRetention")
    public void retention() {
        boolean ran = mutex.run("aiHistoryRetention",() -> {
            var result = job.runCleanup();
            XxlJobHelper.log("expired={} purged={} skipped={} failed={}",result.expired(),result.purged(),result.skipped(),result.failed());
            if (result.failed()>0) throw new IllegalStateException("Some AI records need cleanup retry");
        });
        if (!ran) XxlJobHelper.handleSuccess("SKIPPED_ALREADY_RUNNING");
    }
}
