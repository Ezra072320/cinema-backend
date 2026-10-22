package edu.czu.cinema.capacity;

import edu.czu.cinema.common.exception.CapacityException;
import edu.czu.cinema.domain.order.dto.OrderAddDTO;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.Semaphore;

@Aspect
@Component
@Order(-10)
public class BookingAdmission {
    private static final DefaultRedisScript<Long> ACQUIRE =
            new DefaultRedisScript<>(
                    """
for i,key in ipairs(KEYS) do if redis.call('EXISTS',key)==1 then return 0 end end
for i,key in ipairs(KEYS) do redis.call('PSETEX',key,ARGV[2],ARGV[1]) end
return 1
""",
                    Long.class);
    private static final DefaultRedisScript<Long> RELEASE =
            new DefaultRedisScript<>(
                    """
for i,key in ipairs(KEYS) do if redis.call('GET',key)==ARGV[1] then redis.call('DEL',key) end end
return 1
""",
                    Long.class);
    private final StringRedisTemplate redis;
    private final Semaphore slots;

    public BookingAdmission(
            StringRedisTemplate redis, @Value("${cinema.booking.max-inflight:16}") int inflight) {
        this.redis = redis;
        this.slots = new Semaphore(inflight);
    }

    @Around(
            "execution(* edu.czu.cinema.domain.order.service.impl.OrderServiceImpl.createOrder(..))"
                + " && args(dto)")
    public Object admit(ProceedingJoinPoint call, OrderAddDTO dto) throws Throwable {
        if (!slots.tryAcquire()) throw new CapacityException();
        String token = UUID.randomUUID().toString();
        var keys =
                dto.ticketIds() == null
                        ? List.<String>of()
                        : dto.ticketIds().stream()
                                .map(id -> "cinema:seat-admission:{" + dto.scheduleId() + "}:" + id)
                                .distinct()
                                .sorted()
                                .toList();
        boolean acquired = false;
        try {
            if (keys.isEmpty() || keys.size() > 6) return call.proceed();
            try {
                acquired = Long.valueOf(1).equals(redis.execute(ACQUIRE, keys, token, "10000"));
            } catch (RuntimeException e) {
                throw new CapacityException();
            }
            if (!acquired) throw new CapacityException();
            return call.proceed();
        } finally {
            // This is admission protection; authoritative ownership stays in MySQL's
            // transaction/CAS.
            if (acquired) {
                try {
                    redis.execute(RELEASE, keys, token);
                } catch (RuntimeException ignored) {
                }
            }
            slots.release();
        }
    }
}
