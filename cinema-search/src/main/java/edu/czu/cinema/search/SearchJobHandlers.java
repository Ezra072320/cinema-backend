package edu.czu.cinema.search;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import edu.czu.cinema.jobs.JobMutex;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Rebuild cursor and active alias remain owned by IndexCoordinator across job retries. */
@Component @RequiredArgsConstructor
@ConditionalOnProperty(name="cinema.jobs.mode",havingValue="xxl")
public class SearchJobHandlers {
    private final JobMutex mutex;
    private final IndexCoordinator index;

    @XxlJob("movieIndexRebuild")
    public void rebuild() {
        boolean ran=mutex.run("movieIndexRebuild",() -> {
            checkInterrupted();
            String target=index.beginRebuild();
            if (target==null) {
                XxlJobHelper.handleSuccess("SKIPPED_ALREADY_BUILDING");
                return;
            }
            var state=index.state();
            long deadline=System.nanoTime()+java.time.Duration.ofMinutes(5).toNanos();
            while (true) {
                checkInterrupted();
                if ("READY".equals(state.get("status")) && Objects.equals(target,state.get("active_index"))) {
                    XxlJobHelper.log("state=READY target={} cursor={}",target,state.get("cursor_version"));
                    return;
                }
                if (!Objects.equals(target,state.get("building_index"))) throw new IllegalStateException("Rebuild attempt changed; inspect durable state");
                if (state.get("last_error")!=null) throw new IllegalStateException("Rebuild paused; previous alias and cursor retained for recovery");
                if (System.nanoTime()>deadline) throw new IllegalStateException("Rebuild wait expired; durable cursor retained");
                try { Thread.sleep(1000); }
                catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Rebuild wait cancelled; durable cursor retained");
                }
                state=index.state();
            }
        });
        if (!ran) XxlJobHelper.handleSuccess("SKIPPED_ALREADY_RUNNING");
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Search rebuild wait interrupted");
    }
}
