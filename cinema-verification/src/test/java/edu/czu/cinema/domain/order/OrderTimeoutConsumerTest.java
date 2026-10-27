package edu.czu.cinema.domain.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.czu.cinema.domain.order.entity.Order;
import edu.czu.cinema.domain.order.event.OrderEvent;
import edu.czu.cinema.domain.order.mapper.OrderMapper;
import edu.czu.cinema.domain.order.service.OrderService;
import edu.czu.cinema.domain.order.service.OrderTimeoutConsumer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderTimeoutConsumerTest {
    private final OrderMapper orders=mock(OrderMapper.class);
    private final OrderService service=mock(OrderService.class);
    private final OrderTimeoutConsumer consumer=new OrderTimeoutConsumer(orders,service,new ObjectMapper(),"unused:8081");

    private OrderEvent event(OrderEvent.Kind kind) {
        return new OrderEvent(UUID.randomUUID().toString(),1,kind,12L,7L,"number",1,null,
                Instant.now(),Instant.now().minusSeconds(3600),"test");
    }

    private void order(int status,LocalDateTime createdAt) {
        var order=new Order();order.setId(12L);order.setStatus(status);order.setCreateTime(createdAt);
        when(orders.selectById(12L)).thenReturn(order);
    }

    @Test void anExpiredTimestampInTheMessageCannotCloseAFreshOrder() {
        order(1,LocalDateTime.now());
        assertThat(consumer.handle(event(OrderEvent.Kind.PAYMENT_TIMEOUT))).isFalse();
        verifyNoInteractions(service);
    }

    @Test void aDelayedOrDuplicateReminderCannotCloseAPaidOrder() {
        order(2,LocalDateTime.now().minusHours(1));
        assertThat(consumer.handle(event(OrderEvent.Kind.PAYMENT_TIMEOUT))).isTrue();
        verifyNoInteractions(service);
    }

    @Test void onlyAnExpiredPendingOrderReachesTheExistingAtomicCloseHandler() {
        order(1,LocalDateTime.now().minusMinutes(16));
        assertThat(consumer.handle(event(OrderEvent.Kind.PAYMENT_TIMEOUT))).isTrue();
        verify(service).closeIfExpired(12L);
    }

    @Test void anotherEventKindCannotExecuteTimeoutProcessing() {
        assertThatIllegalArgumentException().isThrownBy(() -> consumer.handle(event(OrderEvent.Kind.PAID)));
        verifyNoInteractions(orders,service);
    }
}
