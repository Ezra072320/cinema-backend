package edu.czu.cinema.common.util;

import cn.hutool.core.date.DateUtil;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 订单号生成器.
 * 格式：yyyyMMddHHmmss + 用户ID后4位 + 6 位随机.
 *
 * <p>V22 给 order.number 加了唯一键. 生成器只负责出号, <b>不保证唯一</b> ——
 * 同秒同用户的 6 位随机段可能撞上, 由调用方(createOrder)捕获 DuplicateKeyException
 * 重新调 {@link #generate} 换随机段重试(≤3 次). 保持本类无状态纯函数.
 */
public final class OrderNumberGenerator {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private OrderNumberGenerator() {
    }

    public static String generate(Long userId) {
        String ts = LocalDateTime.now().format(DTF);
        String suffix = userId == null
                ? ""
                : String.format("%04d", Math.abs(userId) % 10000);
        return ts + suffix + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
    }

    /** 兼容旧签名. */
    public static String generate(Long userId, Object ignored) {
        return generate(userId);
    }

    /** 暴露给 Hutool 调用方使用. */
    public static String today() {
        return DateUtil.today();
    }
}
