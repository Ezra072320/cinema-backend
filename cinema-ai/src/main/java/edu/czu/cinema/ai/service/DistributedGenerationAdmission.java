package edu.czu.cinema.ai.service;

import edu.czu.cinema.common.exception.CapacityException;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;

import java.time.*;
import java.util.*;

/** Per-user/chat leases and daily budgets are shared by all AI replicas. */
@Aspect
@Component
@Order(-10)
public class DistributedGenerationAdmission {
    private static final DefaultRedisScript<Long> ACQUIRE =
            new DefaultRedisScript<>(
                    """
local t=redis.call('TIME');local now=tonumber(t[1])*1000+tonumber(t[2])/1000
redis.call('ZREMRANGEBYSCORE',KEYS[1],'-inf',now)
if redis.call('EXISTS',KEYS[2])==1 or redis.call('ZCARD',KEYS[1])>=2 then return 0 end
if tonumber(redis.call('GET',KEYS[3]) or '0')>=200 then return 0 end
redis.call('PSETEX',KEYS[2],120000,ARGV[1])
redis.call('ZADD',KEYS[1],now+120000,ARGV[1]);redis.call('PEXPIRE',KEYS[1],120000)
redis.call('INCR',KEYS[3]);redis.call('EXPIRE',KEYS[3],172800)
return 1
""",
                    Long.class);
    private static final DefaultRedisScript<Long> RELEASE =
            new DefaultRedisScript<>(
                    """
                    redis.call('ZREM',KEYS[1],ARGV[1])
                    if redis.call('GET',KEYS[2])==ARGV[1] then redis.call('DEL',KEYS[2]) end
                    return 1
                    """,
                    Long.class);
    private final StringRedisTemplate redis;

    public DistributedGenerationAdmission(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Around(
            "execution(* edu.czu.cinema.ai.service.impl.PersistentAiChatService.streamChat(..)) &&"
                + " args(uid,chat,question)")
    public Object stream(ProceedingJoinPoint call, Long uid, String chat, String question)
            throws Throwable {
        String prefix = "cinema:ai:{" + uid + "}:";
        String token = UUID.randomUUID().toString();
        var keys =
                List.of(
                        prefix + "running",
                        prefix + "chat:" + chat,
                        prefix + "daily:" + LocalDate.now(ZoneId.of("Asia/Shanghai")));
        try {
            if (!Long.valueOf(1).equals(redis.execute(ACQUIRE, keys, token)))
                throw new CapacityException();
        } catch (RuntimeException e) {
            throw new CapacityException();
        }
        try {
            return ((Flux<?>) call.proceed())
                    .take(Duration.ofSeconds(110))
                    .doFinally(signal -> release(keys, token));
        } catch (Throwable e) {
            release(keys, token);
            throw e;
        }
    }

    @Around(
            "execution(* edu.czu.cinema.ai.service.impl.PersistentAiChatService.deleteSession(..))"
                + " && args(uid,chat)")
    public Object delete(ProceedingJoinPoint call, Long uid, String chat) throws Throwable {
        String prefix = "cinema:ai:{" + uid + "}:";
        String token = UUID.randomUUID().toString();
        var keys = List.of(prefix + "running", prefix + "chat:" + chat);
        if (!Boolean.TRUE.equals(
                redis.opsForValue().setIfAbsent(keys.get(1), token, Duration.ofSeconds(30))))
            throw new CapacityException();
        try {
            return call.proceed();
        } finally {
            release(keys, token);
        }
    }

    private void release(List<String> keys, String token) {
        try {
            redis.execute(RELEASE, keys.subList(0, 2), token);
        } catch (RuntimeException ignored) {
        }
    }

    /** Maintenance shares the generation/deletion lease without consuming a daily request. */
    public boolean whileSessionIdle(Long uid, String chat, Runnable maintenance) {
        String prefix = "cinema:ai:{" + uid + "}:";
        String token = UUID.randomUUID().toString();
        var keys = List.of(prefix + "running", prefix + "chat:" + chat);
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(keys.get(1),token,Duration.ofSeconds(30))))
            return false;
        try {
            maintenance.run();
            return true;
        } finally {
            release(keys,token);
        }
    }
}
