package edu.czu.cinema.ai;
import edu.czu.cinema.domain.order.service.OrderRefundPolicy;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;

class OrderRefundPolicyTest {
    @Test void boundariesAndRoundingMatchActualRefunds() {
        var now = LocalDateTime.of(2026,10,19,10,0);
        var total = new BigDecimal("99.99");
        assertThat(OrderRefundPolicy.quote(2,total,now.plusMinutes(179),now).eligible()).isFalse();
        assertThat(OrderRefundPolicy.quote(2,total,now.plusMinutes(180),now).amount()).isEqualByComparingTo("69.99");
        assertThat(OrderRefundPolicy.quote(2,total,now.plusMinutes(360),now).amount()).isEqualByComparingTo("69.99");
        assertThat(OrderRefundPolicy.quote(2,total,now.plusMinutes(361),now).amount()).isEqualByComparingTo("99.99");
        for (int status : new int[]{1,3,4,6})
            assertThat(OrderRefundPolicy.quote(status,total,now.plusDays(1),now).eligible()).isFalse();
    }
}
