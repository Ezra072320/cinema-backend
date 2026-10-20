package edu.czu.cinema.domain.order;

import edu.czu.cinema.common.util.OrderNumberGenerator;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * 订单号生成器格式 + 随机段重掷(P1-T5 配合点).
 *
 * <p>格式 yyyyMMddHHmmss(14) + 用户ID后4位 + 6 位随机 = 24 位数字.
 * 生成器本身不保证唯一 —— 同秒随机段可能撞, 唯一性由 order.number 唯一键 + createOrder 捕获
 * DuplicateKeyException 换随机段重试兜底. 此处只验格式与「每次调用重掷随机段」的契约.
 */
class OrderNumberGeneratorTest {

    @Test
    void formatIsTimestampUserSuffixAndRandom() {
        String n = OrderNumberGenerator.generate(77L);
        assertThat(n).hasSize(24).matches("\\d{24}");
    }

    @Test
    void nullUserIdStillProducesOrderNumber() {
        // 系统/无用户上下文也不崩溃(少 4 位用户段)
        String n = OrderNumberGenerator.generate(null);
        assertThat(n).matches("\\d{20}");
    }

    @Test
    void randomSegmentIsReRolledPerCall() {
        // 碰撞重试依赖「每次调用都换随机段」—— 连续多次生成号段应有差异
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 30; i++) {
            seen.add(OrderNumberGenerator.generate(77L));
        }
        assertThat(seen).hasSizeGreaterThan(1);
    }
}