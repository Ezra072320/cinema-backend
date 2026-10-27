package edu.czu.cinema.jobs;

import java.util.concurrent.TimeUnit;
import org.redisson.api.RedissonClient;

/** Jobs keep one lease across manual/scheduled triggers. Redis failure never executes unlocked. */
public class JobMutex {
  private final RedissonClient client;
  private final String owner;

  public JobMutex(RedissonClient client, String owner) {
    this.client = client;
    this.owner = owner;
  }

  public boolean run(String job, Runnable action) {
    if (!job.matches("[a-zA-Z0-9:_-]{1,100}"))
      throw new IllegalArgumentException("Invalid job name");
    var lock = client.getLock("cinema:job:mutex:" + owner + ":" + job);
    boolean acquired = false;
    try {
      acquired = lock.tryLock(0, TimeUnit.SECONDS);
      if (!acquired) return false;
      action.run();
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Job lock interrupted", e);
    } finally {
      // XXL timeout/cancel can interrupt the worker. Let Redis cleanup finish, then restore it.
      boolean interrupted = Thread.interrupted();
      try {
        if (acquired && lock.isHeldByCurrentThread()) lock.unlock();
      } finally {
        if (interrupted) Thread.currentThread().interrupt();
      }
    }
  }
}
