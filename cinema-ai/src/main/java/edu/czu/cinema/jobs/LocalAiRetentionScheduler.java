package edu.czu.cinema.jobs;

import edu.czu.cinema.ai.service.AiDataRetentionJob;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
@ConditionalOnProperty(name="cinema.jobs.mode",havingValue="local",matchIfMissing=true)
public class LocalAiRetentionScheduler {
    private final AiDataRetentionJob job;
    @Scheduled(fixedDelay=3_600_000,initialDelayString="${cinema.jobs.ai-retention.initial-delay-ms:600000}")
    public void cleanup() { job.cleanup(); }
}
