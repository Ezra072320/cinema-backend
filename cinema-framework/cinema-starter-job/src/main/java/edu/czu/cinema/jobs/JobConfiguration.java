package edu.czu.cinema.jobs;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@Configuration
@EnableConfigurationProperties(JobProperties.class)
public class JobConfiguration {
  @Bean(destroyMethod = "shutdown")
  @ConditionalOnProperty(name = "cinema.jobs.mode", havingValue = "xxl")
  RedissonClient jobRedisson(
      @Value("${spring.data.redis.host}") String host,
      @Value("${spring.data.redis.port:6379}") int port,
      @Value("${spring.data.redis.password:}") String password) {
    var config = new Config();
    config.setThreads(2).setNettyThreads(2).setLockWatchdogTimeout(30000);
    var single =
        config
            .useSingleServer()
            .setAddress("redis://" + host + ":" + port)
            .setConnectionMinimumIdleSize(1)
            .setConnectionPoolSize(6)
            .setSubscriptionConnectionMinimumIdleSize(1)
            .setSubscriptionConnectionPoolSize(2)
            .setTimeout(1500)
            .setConnectTimeout(1500)
            .setRetryAttempts(1)
            .setDatabase(0);
    if (!password.isBlank()) single.setPassword(password);
    return Redisson.create(config);
  }

  @Bean
  @ConditionalOnProperty(name = "cinema.jobs.mode", havingValue = "xxl")
  JobMutex jobMutex(RedissonClient client, @Value("${spring.application.name}") String owner) {
    return new JobMutex(client, owner);
  }

  @Bean
  @ConditionalOnProperty(name = "cinema.jobs.mode", havingValue = "xxl")
  XxlJobSpringExecutor jobExecutor(
      @Value("${cinema.jobs.admin-addresses}") String admin,
      @Value("${cinema.jobs.access-token}") String token,
      @Value("${spring.application.name}") String name,
      @Value("${cinema.jobs.executor-address:}") String address,
      @Value("${cinema.jobs.log-path:${java.io.tmpdir}/cinema-job-logs}") String logs) {
    if (token.length() < 32)
      throw new IllegalStateException("Job callback token must contain at least 32 characters");
    var executor = new XxlJobSpringExecutor();
    executor.setAdminAddresses(admin);
    executor.setAppname(name);
    executor.setAddress(address);
    executor.setPort(9999);
    executor.setAccessToken(token);
    executor.setLogPath(logs);
    executor.setLogRetentionDays(14);
    return executor;
  }
}
