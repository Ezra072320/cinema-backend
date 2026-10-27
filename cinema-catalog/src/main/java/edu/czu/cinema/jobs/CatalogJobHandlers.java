package edu.czu.cinema.jobs;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import edu.czu.cinema.domain.movie.sync.MovieSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Waits for the existing leased worker; a successful callback includes its actual outcome. */
@Component @RequiredArgsConstructor
@ConditionalOnProperty(name="cinema.jobs.mode",havingValue="xxl")
public class CatalogJobHandlers {
    private final JobMutex mutex;
    private final MovieSyncService sync;

    static int batchSize(String parameter) {
        String value=parameter==null ? "" : parameter.trim();
        if (value.isEmpty()) return 1;
        if (!value.matches("[1-9]|[1-4][0-9]|50")) throw new IllegalArgumentException("batch size must be an integer from 1 to 50");
        return Integer.parseInt(value);
    }

    @XxlJob("movieUpcomingSync")
    public void upcoming() {
        int limit=batchSize(XxlJobHelper.getJobParam());
        boolean ran=mutex.run("movieUpcomingSync",() -> {
            checkInterrupted();
            if (!sync.start(limit)) {
                XxlJobHelper.handleSuccess("SKIPPED_ALREADY_RUNNING");
                return;
            }
            var state=sync.status();
            long runId=state.startedAt();
            long deadline=System.nanoTime()+java.time.Duration.ofMinutes(15).toNanos();
            while (true) {
                checkInterrupted();
                if (state.startedAt()!=runId) throw new IllegalStateException("Sync attempt changed; inspect persisted progress");
                if (!state.running()) {
                    XxlJobHelper.log("state={} total={} done={} created={} updated={} skipped={} partial={} failed={}",
                            state.state(),state.total(),state.done(),state.created(),state.updated(),state.skipped(),state.partial(),state.fail());
                    if (!"COMPLETED".equals(state.state()) || state.fail()>0)
                        throw new IllegalStateException("Movie sync has failed items; inspect movie progress before retrying");
                    return;
                }
                if (System.nanoTime()>deadline) throw new IllegalStateException("Sync wait expired; accepted worker progress remains available");
                try { Thread.sleep(1000); }
                catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Sync wait cancelled; accepted worker progress remains available");
                }
                state=sync.status();
            }
        });
        if (!ran) XxlJobHelper.handleSuccess("SKIPPED_ALREADY_RUNNING");
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Movie sync wait interrupted");
    }
}
