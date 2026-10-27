package edu.czu.cinema.domain.captcha;

import edu.czu.cinema.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 验证码一次性消费（P1-S3）与登录失败锁定. */
class CaptchaAndLoginRiskTest {
    @Test
    void startupRendererWarmupDoesNotIssueOrConsumeAnyRedisTicket() {
        StringRedisTemplate untouched = mock(StringRedisTemplate.class);
        assertThatCode(() -> new CaptchaService(untouched).warmRenderer()).doesNotThrowAnyException();
        verifyNoInteractions(untouched);
    }

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    private CaptchaService captcha() {
        when(redis.opsForValue()).thenReturn(ops);
        return new CaptchaService(redis);
    }

    @Test
    void ticketIsSingleUseAndConsumedEvenOnWrongCode() {
        // 第一次 getAndDelete 返回正确答案，第二次起为 null（已被消费）
        when(ops.getAndDelete(anyString())).thenReturn("A1B2", (String) null);

        assertThat(captcha().verifyAndConsume("t1", "A1b2")).isTrue(); // 大小写不敏感，通过
        assertThat(captcha().verifyAndConsume("t1", "A1b2")).isFalse(); // 同一票据已被消费
        // 错误答案同样销毁票据
        when(ops.getAndDelete(anyString())).thenReturn("C3D4", (String) null);
        assertThat(captcha().verifyAndConsume("t2", "wrong")).isFalse();
        assertThat(captcha().verifyAndConsume("t2", "C3D4")).isFalse(); // 失败也消耗
        // 每张票据都走 GETDEL（原子取出即删），而非 GET+手动 delete 两步
        verify(ops, times(4)).getAndDelete(anyString());
    }

    @Test
    void expiredOrConsumedTicketFails() {
        when(ops.getAndDelete(anyString())).thenReturn(null);
        assertThat(captcha().verifyAndConsume("gone", "A1B2")).isFalse();
    }

    @Test
    void generateRateLimitsPerIpPerMinute() {
        // 超过阈值时 increment 返回阈值+1，应抛业务异常、不再往下发图
        when(ops.increment(anyString())).thenReturn((long) CaptchaService.MAX_GENERATE_PER_MINUTE + 1);
        assertThatThrownBy(() -> captcha().generate("t", "1.2.3.4"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("频繁");
    }

    @Test
    void fiveFailuresTriggersLockAndCheckLockedBlocks() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment("login:fail:10.0.0.1:alice")).thenReturn(1L, 2L, 3L, 4L, 5L);
        when(redis.hasKey(anyString())).thenReturn(false); // 锁定前不拦截

        LoginRiskService risk = new LoginRiskService(redis);
        for (int i = 0; i < LoginRiskService.MAX_FAILURES; i++) {
            risk.recordFailure("10.0.0.1", "alice");
        }
        // 连续 5 次后应写入锁定 key
        verify(ops).set(eq("login:lock:10.0.0.1:alice"), eq("1"), eq(Duration.ofMinutes(10)));

        // 锁定期间 checkLocked 拦截并给出明确提示
        when(redis.hasKey("login:lock:10.0.0.1:alice")).thenReturn(true);
        assertThatThrownBy(() -> risk.checkLocked("10.0.0.1", "alice"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("锁定");
    }

    @Test
    void successfulLoginClearsFailureCounter() {
        when(redis.opsForValue()).thenReturn(ops);
        LoginRiskService risk = new LoginRiskService(redis);
        risk.clearFailures("10.0.0.1", "alice");
        verify(redis).delete("login:fail:10.0.0.1:alice");
    }
}
