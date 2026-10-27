package edu.czu.cinema.domain.captcha;

import com.wf.captcha.SpecCaptcha;
import edu.czu.cinema.common.api.ResultCode;
import edu.czu.cinema.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import jakarta.annotation.PostConstruct;

/**
 * 图形验证码服务.
 * <p>
 * 设计要点:
 * <ul>
 *   <li>票据制(ticket-based):ticket 由前端 UUID 生成,服务端只关心 "按 ticket 存 code".</li>
 *   <li>无状态:不依赖 HttpSession,与 JWT+STATELESS 模式一致.</li>
 *   <li>一次性消费:GETDEL 原子取出并销毁——无论本次比对对错票据都失效,
 *       4 位码无法在同一票据上反复暴力试。</li>
 *   <li>生成频控:同一 IP 每分钟上限,防脚本批量刷图。</li>
 *   <li>同时供 {@link CaptchaController}(图片)与 login/register 端点(原子校验)复用。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaptchaService {

    /** Redis key 前缀: 与登录失败计数等其他业务 key 保持命名空间隔离. */
    public static final String REDIS_KEY_PREFIX = "captcha:";

    /** 生成频控 key 前缀（按 IP+分钟窗口计数）. */
    public static final String GENERATE_RATE_PREFIX = "captcha:gen:";

    /** 5 分钟 TTL — 足够用户输入,又不至于长期占用 Redis. */
    public static final Duration TTL = Duration.ofMinutes(5);

    /** 同一 IP 每分钟最多生成的张数. */
    public static final int MAX_GENERATE_PER_MINUTE = 30;

    /** 验证码默认规格, 与前端 img 渲染尺寸匹配. */
    public static final int CAPTCHA_WIDTH = 120;
    public static final int CAPTCHA_HEIGHT = 40;
    public static final int CAPTCHA_LENGTH = 4;

    private final StringRedisTemplate redisTemplate;

    /** Initialize AWT fonts/ImageIO before readiness, without issuing any Redis ticket. */
    @PostConstruct
    public void warmRenderer() {
        render(new SpecCaptcha(CAPTCHA_WIDTH, CAPTCHA_HEIGHT, CAPTCHA_LENGTH));
        log.info("Captcha image renderer is ready");
    }

    private byte[] render(SpecCaptcha captcha) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!captcha.out(output) || output.size() < 8) {
            throw new IllegalStateException("Captcha image renderer is unavailable");
        }
        return output.toByteArray();
    }

    /**
     * 生成一张验证码图片并把答案写入 Redis（含同 IP 频控）.
     *
     * @param ticket   前端生成的 UUID
     * @param clientIp 客户端 IP，用于生成频控
     * @return PNG 字节数组
     */
    public byte[] generate(String ticket, String clientIp) {
        if (ticket == null || ticket.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "ticket 不能为空");
        }
        guardGenerateRate(clientIp);
        SpecCaptcha captcha = new SpecCaptcha(CAPTCHA_WIDTH, CAPTCHA_HEIGHT, CAPTCHA_LENGTH);
        String code = captcha.text();
        byte[] png = render(captcha);
        redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + ticket, code, TTL);
        log.debug("captcha issued: ticket={}, ttl={}m", ticket, TTL.toMinutes());
        return png;
    }

    /**
     * 校验并一次性消费验证码.
     *
     * <p>设计:大小写不敏感,避免用户在大小写混淆上反复失败。
     * GETDEL 取出即销毁（失败同样消耗票据），比"先 GET 再 DELETE"少一次竞态窗口——
     * 并发请求最多一个能取到答案。</p>
     *
     * @param ticket 取图时前端生成的 UUID
     * @param code   用户输入的字符
     * @return true 表示验证通过
     */
    public boolean verifyAndConsume(String ticket, String code) {
        if (ticket == null || ticket.isBlank() || code == null || code.isBlank()) {
            return false;
        }
        String key = REDIS_KEY_PREFIX + ticket;
        // 原子消费：取出即删除，票据一次性；取不到 = 已过期/已被消费
        String saved = redisTemplate.opsForValue().getAndDelete(key);
        if (saved == null) {
            return false;
        }
        return saved.equalsIgnoreCase(code.trim());
    }

    /** 同 IP 每分钟生成上限：incr 计数 + 首次设窗口过期，超限直接业务异常. */
    private void guardGenerateRate(String clientIp) {
        String ip = clientIp == null || clientIp.isBlank() ? "unknown" : clientIp;
        // 按分钟窗口分桶，窗口滚动时旧 key 自然过期，无需定时清理
        long minute = System.currentTimeMillis() / 60_000L;
        String key = GENERATE_RATE_PREFIX + ip + ":" + minute;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, Duration.ofSeconds(70));
        }
        if (count != null && count > MAX_GENERATE_PER_MINUTE) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "验证码请求过于频繁，请稍后再试");
        }
    }
}
