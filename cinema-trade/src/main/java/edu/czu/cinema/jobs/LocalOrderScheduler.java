package edu.czu.cinema.jobs;

import edu.czu.cinema.domain.order.service.OrderCloseJob;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "cinema.jobs.mode", havingValue = "local", matchIfMissing = true)
public class LocalOrderScheduler {
  private final OrderCloseJob job;

  public LocalOrderScheduler(OrderCloseJob job) {
    this.job = job;
  }

  @Scheduled(fixedDelay = 60000, initialDelay = 60000)
  public void sweep() {
    job.sweep();
  }
}
