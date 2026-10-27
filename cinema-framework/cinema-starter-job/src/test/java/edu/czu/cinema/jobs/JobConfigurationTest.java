package edu.czu.cinema.jobs;

import static org.assertj.core.api.Assertions.*;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class JobConfigurationTest {
  @Test
  void localModeHasNoSchedulerOrSecondRedisNetworkClient() {
    new ApplicationContextRunner()
        .withUserConfiguration(JobConfiguration.class)
        .run(
            c -> {
              assertThat(c).hasNotFailed();
              assertThat(c).doesNotHaveBean(RedissonClient.class);
              assertThat(c).doesNotHaveBean(XxlJobSpringExecutor.class);
            });
  }

  @Test
  void misspelledModeFailsRatherThanSilentlyDisablingExpiryCompensation() {
    new ApplicationContextRunner()
        .withUserConfiguration(JobConfiguration.class)
        .withPropertyValues("cinema.jobs.mode=typo")
        .run(c -> assertThat(c).hasFailed());
  }
}
