package edu.czu.cinema.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import java.time.Duration;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Token-protected, local-only job experiment. It never changes ticket/order business data. */
@Component
@ConditionalOnProperty(name = "cinema.jobs.lab.enabled", havingValue = "true")
public class JobMutexProbe {
  private final JobMutex mutex;
  private final RedissonClient redis;
  private final ObjectMapper json;

  public JobMutexProbe(JobMutex mutex, RedissonClient redis, ObjectMapper json) {
    this.mutex = mutex;
    this.redis = redis;
    this.json = json;
  }

  @XxlJob("jobMutexProbe")
  public void probe() throws Exception {
    var p = json.readTree(XxlJobHelper.getJobParam());
    String id = p.path("probeId").asText();
    int delay = p.path("delayMs").asInt();
    if (!id.matches("[a-f0-9]{16}") || delay < 0 || delay > 10000)
      throw new IllegalArgumentException("Invalid local job experiment");
    boolean ran =
        mutex.run(
            "probe:" + id,
            () -> {
              var count = redis.getAtomicLong("cinema:job:probe:" + id + ":runs");
              long n = count.incrementAndGet();
              count.expire(Duration.ofMinutes(10));
              XxlJobHelper.log("probe={} actualRun={}", id, n);
              try {
                Thread.sleep(delay);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Probe interrupted", e);
              }
              if (p.path("fail").asBoolean())
                throw new IllegalStateException("Requested local job failure");
            });
    if (!ran) XxlJobHelper.handleSuccess("SKIPPED_ALREADY_RUNNING");
  }
}
