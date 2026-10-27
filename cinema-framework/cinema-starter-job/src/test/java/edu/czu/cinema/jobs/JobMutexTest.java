package edu.czu.cinema.jobs;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

class JobMutexTest {
  @Test
  void cancelledActionReleasesLeaseWithoutLosingInterruptSignal() throws Exception {
    var guard=mutex();
    when(lock.tryLock(0,TimeUnit.SECONDS)).thenReturn(true);
    when(lock.isHeldByCurrentThread()).thenAnswer(call->{assertThat(Thread.currentThread().isInterrupted()).isFalse();return true;});
    try {
      assertThatThrownBy(()->guard.run("expiry",()->{Thread.currentThread().interrupt();throw new IllegalStateException("cancelled");})).hasMessage("cancelled");
      verify(lock).unlock();
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {Thread.interrupted();}
  }
  RedissonClient client = mock(RedissonClient.class);
  RLock lock = mock(RLock.class);

  JobMutex mutex() {
    when(client.getLock(anyString())).thenReturn(lock);
    return new JobMutex(client, "cinema-trade");
  }

  @Test
  void concurrentOwnerIsSkippedWithoutExecutingOrUnlocking() throws Exception {
    var guard = mutex();
    when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(false);
    var calls = new AtomicInteger();
    assertThat(guard.run("expiry", calls::incrementAndGet)).isFalse();
    assertThat(calls.get()).isZero();
    verify(lock, never()).unlock();
    verify(lock, never()).forceUnlock();
  }

  @Test
  void failureStillReleasesOnlyItsOwnLease() throws Exception {
    var guard = mutex();
    when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);
    when(lock.isHeldByCurrentThread()).thenReturn(true);
    assertThatThrownBy(
            () ->
                guard.run(
                    "expiry",
                    () -> {
                      throw new IllegalStateException("business failed");
                    }))
        .hasMessage("business failed");
    verify(lock).unlock();
    verify(lock, never()).forceUnlock();
  }

  @Test
  void redisFailureNeverFallsBackToExecutingUnlocked() throws Exception {
    var guard = mutex();
    when(lock.tryLock(0, TimeUnit.SECONDS))
        .thenThrow(new IllegalStateException("Redis unavailable"));
    var calls = new AtomicInteger();
    assertThatThrownBy(() -> guard.run("expiry", calls::incrementAndGet))
        .hasMessage("Redis unavailable");
    assertThat(calls.get()).isZero();
    verify(lock, never()).unlock();
  }

  @Test
  void interruptedWaitPreservesTheInterruptFlag() throws Exception {
    var guard = mutex();
    when(lock.tryLock(0, TimeUnit.SECONDS)).thenThrow(new InterruptedException());
    try {
      assertThatThrownBy(() -> guard.run("expiry", () -> {})).hasMessage("Job lock interrupted");
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }
}
