package edu.czu.cinema.governance;

import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import edu.czu.cinema.common.exception.CapacityException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Both portal and trusted AI reads cross the same schedule-owned resource. No writes are retried.
 */
@Aspect
@Component
@ConditionalOnProperty(name = "cinema.governance.enabled", havingValue = "true")
public class SeatQueryAdmission {
  @Around(
      "execution(*"
          + " edu.czu.cinema.domain.schedule.service.impl.ScheduleServiceImpl.seatsOfSchedule(..))")
  public Object query(ProceedingJoinPoint call) throws Throwable {
    try (var entry = SphU.entry(SeatRuleCodec.RESOURCE)) {
      return call.proceed();
    } catch (BlockException blocked) {
      throw new CapacityException();
    }
  }
}
