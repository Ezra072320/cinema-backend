package edu.czu.cinema.jobs;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import edu.czu.cinema.domain.order.service.OrderCloseJob;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "cinema.jobs.mode", havingValue = "xxl")
public class TradeJobHandlers {
  private final JobMutex mutex;
  private final OrderCloseJob orders;

  public TradeJobHandlers(JobMutex mutex, OrderCloseJob orders) {
    this.mutex = mutex;
    this.orders = orders;
  }

  @XxlJob("orderExpiryReconcile")
  public void orderExpiryReconcile() {
    boolean ran =
        mutex.run(
            "orderExpiryReconcile",
            () -> {
              var result = orders.runSweep();
              XxlJobHelper.log(
                  "closed={}, reconciled={}, failed={}",
                  result.closed(),
                  result.reconciled(),
                  result.failed());
              if (result.failed() > 0)
                throw new IllegalStateException(
                    "Some orders need retry; successful independent transactions remain committed");
            });
    if (!ran) XxlJobHelper.handleSuccess("SKIPPED_ALREADY_RUNNING");
  }
}
