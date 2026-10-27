package edu.czu.cinema.domain.order;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import edu.czu.cinema.domain.order.entity.OrderAction;
import edu.czu.cinema.domain.order.mapper.OrderActionMapper;
import edu.czu.cinema.domain.order.service.OrderAuditService;
import edu.czu.cinema.domain.order.service.OrderEventWriter;
import java.math.BigDecimal;
import org.apache.seata.core.context.RootContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.slf4j.LoggerFactory;

class OrderAuditPrivacyTest {
    @Test void auditKeepsItsProtectedReasonButLogsOnlyTransactionCorrelation() {
        var mapper = mock(OrderActionMapper.class);
        var service = new OrderAuditService(mapper,
                new StaticListableBeanFactory().getBeanProvider(OrderEventWriter.class));
        Logger logger = (Logger) LoggerFactory.getLogger(OrderAuditService.class);
        var logs = new ListAppender<ILoggingEvent>(); logs.start(); logger.addAppender(logs);
        RootContext.bind("unit-seata-xid-7301");
        try {
            service.record(7301L, "test-order-7301", OrderAction.ACTION_REFUND, 2, 3,
                    new BigDecimal("12.50"), "USER", 42L, "private-free-text-refund-reason");
            var captured = ArgumentCaptor.forClass(OrderAction.class);
            verify(mapper).insert(captured.capture());
            assertThat(captured.getValue().getReason()).isEqualTo("private-free-text-refund-reason");
            assertThat(logs.list).hasSize(1);
            assertThat(logs.list.getFirst().getFormattedMessage()).doesNotContain("private-free-text-refund-reason")
                    .contains("test-order-7301");
            assertThat(logs.list.getFirst().getKeyValuePairs()).anySatisfy(pair -> {
                assertThat(pair.key).isEqualTo("order_id"); assertThat(pair.value).isEqualTo(7301L);
            }).anySatisfy(pair -> {
                assertThat(pair.key).isEqualTo("xid"); assertThat(pair.value).isEqualTo("unit-seata-xid-7301");
            });
        } finally { RootContext.unbind(); logger.detachAppender(logs); }
    }
}
