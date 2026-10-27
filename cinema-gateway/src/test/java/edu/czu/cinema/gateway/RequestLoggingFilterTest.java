package edu.czu.cinema.gateway;

import static org.assertj.core.api.Assertions.*;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import edu.czu.cinema.observe.RequestLog;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class RequestLoggingFilterTest {
    @Test void forwardsAStableRequestIdAndOmitsTheQuery() {
        withLogs(logs -> {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/movie/topSales?token=never-log-me"));
            new RequestLoggingFilter().filter(exchange, request -> {
                assertThat(request.getRequest().getHeaders().getFirst("X-Trace-Id")).isNotBlank();
                return Mono.empty();
            }).block();
            assertThat(logs.list).hasSize(1);
            assertThat(logs.list.getFirst().getKeyValuePairs().toString()).doesNotContain("never-log-me");
            assertThat(exchange.getResponse().getHeaders().getFirst("X-Trace-Id")).isNotBlank();
        });
    }

    @Test void failureAndCancellationProduceTheirOwnTerminalStatuses() {
        withLogs(logs -> {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/movie/topSales"));
            assertThatThrownBy(() -> new RequestLoggingFilter().filter(exchange,
                    request -> Mono.error(new IllegalStateException("unavailable"))).block()).isInstanceOf(IllegalStateException.class);
            var cancelled = MockServerWebExchange.from(MockServerHttpRequest.get("/api/ai/chat"));
            var subscription = new RequestLoggingFilter().filter(cancelled, request -> Mono.never()).subscribe();
            subscription.dispose();
            assertThat(logs.list).hasSize(2);
            assertThat(logs.list.get(0).getKeyValuePairs()).anySatisfy(pair -> {
                assertThat(pair.key).isEqualTo("status"); assertThat(pair.value).isEqualTo(500);
            });
            assertThat(logs.list.get(1).getKeyValuePairs()).anySatisfy(pair -> {
                assertThat(pair.key).isEqualTo("status"); assertThat(pair.value).isEqualTo(499);
            });
        });
    }

    private void withLogs(java.util.function.Consumer<ListAppender<ILoggingEvent>> check) {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestLog.class);
        var logs = new ListAppender<ILoggingEvent>(); logs.start(); logger.addAppender(logs);
        try { check.accept(logs); } finally { logger.detachAppender(logs); }
    }
}
