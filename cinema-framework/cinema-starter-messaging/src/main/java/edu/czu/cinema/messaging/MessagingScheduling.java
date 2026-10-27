package edu.czu.cinema.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** A slow broker must not occupy the timeout reconciliation job's scheduler. */
@Configuration
@ConditionalOnProperty(name = "cinema.messaging.enabled", havingValue = "true")
public class MessagingScheduling {
  @Bean("messagingScheduler")
  public ThreadPoolTaskScheduler messagingScheduler() {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(2);
    scheduler.setThreadNamePrefix("messaging-");
    scheduler.setAwaitTerminationSeconds(5);
    return scheduler;
  }

  @Bean("taskScheduler")
  public ThreadPoolTaskScheduler businessScheduler() {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("business-jobs-");
    return scheduler;
  }
}
