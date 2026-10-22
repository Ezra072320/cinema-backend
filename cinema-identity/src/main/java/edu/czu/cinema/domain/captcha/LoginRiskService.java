package edu.czu.cinema.domain.captcha;

import edu.czu.cinema.common.api.ResultCode;
import edu.czu.cinema.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 登录失败风控：「IP + 账号」双维度计数与锁定.
 * <p>
 * 全站无登录限流时，撞库脚本可以无限次试口令（P1-S3）。这里用 Redis 记录
 * 连续失败次数，达到阈值即锁定一段时间；key 自带过期，无需定时任务清理。
 * 计数粒度取 IP+账号：同一账号换 IP 不共享计数（避免误伤 NAT 后的正常用户），
 * 同一 IP 换账号也各算各的（防止单 IP 撞库全站仍要另设 IP 维度上限时可扩展）。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class LoginRiskService {

    /** 连续失败达到该次数即锁定. */
    public static final int MAX_FAILURES = 5;

    /** 锁定时长. */
    public static final Duration LOCK_DURATION = Duration.ofMinutes(10);

    private static final String FAIL_PREFIX = "login:fail:";
    private static final String LOCK_PREFIX = "login:lock:";

    private final StringRedisTemplate redisTemplate;

    /**
     * 登录前检查是否处于锁定期，锁定中直接业务异常（明确提示，不回「口令错误」）.
     */
    public void checkLocked(String ip, String account) {
        if (Boolean.TRUE.equals(redisTemplate.hasKey(lockKey(ip, account)))) {
            throw new BusinessException(ResultCode.FORBIDDEN,
                    "登录失败次数过多，账号已临时锁定，请 " + LOCK_DURATION.toMinutes() + " 分钟后再试");
        }
    }

    /**
     * 记一次登录失败：累计到阈值转为锁定，失败计数随之销毁（重新计数要等锁定过期）.
     */
    public void recordFailure(String ip, String account) {
        String failKey = failKey(ip, account);
        Long count = redisTemplate.opsForValue().increment(failKey);
        if (count != null && count == 1L) {
            // 失败计数窗口与锁定时长一致：孤立失败不会无限期累计
            redisTemplate.expire(failKey, LOCK_DURATION);
        }
        if (count != null && count >= MAX_FAILURES) {
            redisTemplate.opsForValue().set(lockKey(ip, account), "1", LOCK_DURATION);
            redisTemplate.delete(failKey);
        }
    }

    /** 登录成功后清零失败计数（锁定 key 不主动删，等自然过期，防止成功口令洗掉仍在观察的锁）. */
    public void clearFailures(String ip, String account) {
        redisTemplate.delete(failKey(ip, account));
    }

    /**
     * 解析客户端 IP：优先取反代写入的 X-Forwarded-For 首个地址，
     * 否则用 TCP 对端地址。频控/限流都以此为维度。
     */
    public static String resolveClientIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    private static String failKey(String ip, String account) {
        return FAIL_PREFIX + ip + ":" + account;
    }

    private static String lockKey(String ip, String account) {
        return LOCK_PREFIX + ip + ":" + account;
    }
}
