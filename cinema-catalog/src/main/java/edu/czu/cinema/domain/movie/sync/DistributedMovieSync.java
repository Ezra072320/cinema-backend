package edu.czu.cinema.domain.movie.sync;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

@Aspect
@Component
public class DistributedMovieSync {
    private static final String LOCK = "cinema:movie-sync:owner",
            STATUS = "cinema:movie-sync:status";
    private static final DefaultRedisScript<Long> RENEW =
            new DefaultRedisScript<>(
                    "if redis.call('GET',KEYS[1])==ARGV[1] then"
                            + " redis.call('EXPIRE',KEYS[1],30);return 1 end;return 0",
                    Long.class);
    private static final DefaultRedisScript<Long> RELEASE =
            new DefaultRedisScript<>(
                    "if redis.call('GET',KEYS[1])==ARGV[1] then redis.call('DEL',KEYS[1]);return 1"
                            + " end;return 0",
                    Long.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final MovieSyncService sync;
    private volatile String owner;

    public DistributedMovieSync(
            StringRedisTemplate redis,
            ObjectMapper json,
            @org.springframework.context.annotation.Lazy MovieSyncService sync) {
        this.redis = redis;
        this.json = json;
        this.sync = sync;
    }

    @Around(
            "execution(* edu.czu.cinema.domain.movie.sync.MovieSyncService.start(..)) ||"
                + " execution(* edu.czu.cinema.domain.movie.sync.MovieSyncService.startRepair(..))")
    public synchronized Object start(ProceedingJoinPoint call) throws Throwable {
        String token = UUID.randomUUID().toString();
        if (!Boolean.TRUE.equals(
                redis.opsForValue().setIfAbsent(LOCK, token, Duration.ofSeconds(30)))) return false;
        owner = token;
        ((MovieSyncService) call.getTarget())
                .bindOwnershipCheck(() -> token.equals(redis.opsForValue().get(LOCK)));
        try {
            Object started = call.proceed();
            if (!Boolean.TRUE.equals(started)) {
                redis.execute(RELEASE, List.of(LOCK), token);
                owner = null;
            } else {
                redis.opsForValue()
                        .set(
                                STATUS,
                                json.writeValueAsString(
                                        ((MovieSyncService) call.getTarget()).status()),
                                Duration.ofDays(1));
            }
            return started;
        } catch (Throwable e) {
            redis.execute(RELEASE, List.of(LOCK), token);
            owner = null;
            throw e;
        }
    }

    @Around("execution(* edu.czu.cinema.domain.movie.sync.MovieSyncService.status(..))")
    public Object status(ProceedingJoinPoint call) throws Throwable {
        if (owner != null) return call.proceed();
        String shared = redis.opsForValue().get(STATUS);
        if (shared == null) return call.proceed();
        var status = json.readValue(shared, MovieSyncStatus.class);
        if (status.running() && !Boolean.TRUE.equals(redis.hasKey(LOCK)))
            return new MovieSyncStatus(
                    false,
                    status.total(),
                    status.done(),
                    status.ok(),
                    status.fail(),
                    status.current(),
                    status.startedAt(),
                    System.currentTimeMillis(),
                    status.results(),
                    status.created(),
                    status.updated(),
                    status.skipped(),
                    status.partial(),
                    status.pending(),
                    "任务节点已离线，可重新发起；已完成资料保留",
                    "FAILED",
                    status.mode());
        return status;
    }

    @Scheduled(fixedDelay = 2000)
    public void publish() {
        String token = owner;
        if (token == null) return;
        try {
            if (!Long.valueOf(1).equals(redis.execute(RENEW, List.of(LOCK), token))) {
                owner = null;
                return;
            }
            MovieSyncStatus status = sync.status();
            redis.opsForValue().set(STATUS, json.writeValueAsString(status), Duration.ofDays(1));
            if (!status.running()) {
                redis.execute(RELEASE, List.of(LOCK), token);
                owner = null;
            }
        } catch (Exception ignored) {
            owner = null;
            /* lease expires and status reports failure if Redis or owner is lost */
        }
    }
}
