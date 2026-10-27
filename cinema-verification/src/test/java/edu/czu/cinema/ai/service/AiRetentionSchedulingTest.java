package edu.czu.cinema.ai.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import edu.czu.cinema.jobs.AiJobHandlers;
import edu.czu.cinema.jobs.JobMutex;
import edu.czu.cinema.jobs.LocalAiRetentionScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AiRetentionSchedulingTest {
    ApplicationContextRunner context() {
        return new ApplicationContextRunner().withUserConfiguration(LocalAiRetentionScheduler.class,AiJobHandlers.class)
                .withBean(AiDataRetentionJob.class,() -> mock(AiDataRetentionJob.class))
                .withBean(JobMutex.class,() -> mock(JobMutex.class));
    }
    @Test void defaultLocalModeHasOnlyOneTrigger() {
        context().run(c -> {
            assertThat(c).hasSingleBean(LocalAiRetentionScheduler.class);
            assertThat(c).doesNotHaveBean(AiJobHandlers.class);
        });
    }
    @Test void remoteModeDisablesLocalTimer() {
        context().withPropertyValues("cinema.jobs.mode=xxl").run(c -> {
            assertThat(c).doesNotHaveBean(LocalAiRetentionScheduler.class);
            assertThat(c).hasSingleBean(AiJobHandlers.class);
        });
    }
}
