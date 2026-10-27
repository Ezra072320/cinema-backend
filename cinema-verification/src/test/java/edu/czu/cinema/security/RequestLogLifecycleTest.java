package edu.czu.cinema.security;

import static org.assertj.core.api.Assertions.*;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import edu.czu.cinema.common.util.TraceIdFilter;
import edu.czu.cinema.observe.RequestLog;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockAsyncContext;
import jakarta.servlet.AsyncEvent;
import org.springframework.web.servlet.HandlerMapping;

class RequestLogLifecycleTest {
    @Test void asyncFailureWaitsForTheFinalResponseStatus() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestLog.class);
        var logs = new ListAppender<ILoggingEvent>(); logs.start(); logger.addAppender(logs);
        try {
            var request = new MockHttpServletRequest("POST", "/api/ai/chat");
            request.setAsyncSupported(true);
            var response = new MockHttpServletResponse();
            new TraceIdFilter().doFilter(request, response, (req, res) -> request.startAsync(request, response));
            MockAsyncContext context = (MockAsyncContext) request.getAsyncContext();
            for (var listener : context.getListeners()) listener.onError(new AsyncEvent(context, request, response));
            assertThat(logs.list).isEmpty();
            response.setStatus(503);
            context.complete();
            assertThat(logs.list).hasSize(1);
            assertThat(logs.list.getFirst().getKeyValuePairs()).anySatisfy(pair -> {
                assertThat(pair.key).isEqualTo("status"); assertThat(pair.value).isEqualTo(503);
            });
        } finally { logger.detachAppender(logs); MDC.clear(); }
    }

    @Test void asyncResponseLogsAtCompletionAndDoesNotLeakMdcOnInitialThread() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestLog.class);
        var logs = new ListAppender<ILoggingEvent>(); logs.start(); logger.addAppender(logs);
        try {
            var request = new MockHttpServletRequest("POST", "/api/ai/chat");
            request.setAsyncSupported(true);
            var response = new MockHttpServletResponse();
            new TraceIdFilter().doFilter(request, response, (req, res) -> {
                request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/ai/chat");
                request.startAsync(request, response);
            });
            assertThat(logs.list).isEmpty();
            assertThat(MDC.get("traceId")).isNull();
            assertThat(response.getHeader("X-Trace-Id")).isNotBlank();
            request.getAsyncContext().complete();
            assertThat(logs.list).hasSize(1);
            assertThat(logs.list.getFirst().getKeyValuePairs()).anySatisfy(pair -> {
                assertThat(pair.key).isEqualTo("route"); assertThat(pair.value).isEqualTo("/api/ai/chat");
            });
        } finally { logger.detachAppender(logs); MDC.clear(); }
    }

    @Test void readinessProducesNoAccessLog() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestLog.class);
        var logs = new ListAppender<ILoggingEvent>(); logs.start(); logger.addAppender(logs);
        try {
            new TraceIdFilter().doFilter(new MockHttpServletRequest("GET", "/actuator/health/readiness"),
                    new MockHttpServletResponse(), (req, res) -> {});
            assertThat(logs.list).isEmpty();
        } finally { logger.detachAppender(logs); MDC.clear(); }
    }

    @Test void internalFeignRequestAlsoRecordsItsMappedRouteAndActualStatus() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestLog.class);
        var logs = new ListAppender<ILoggingEvent>(); logs.start(); logger.addAppender(logs);
        try {
            var request = new MockHttpServletRequest("GET", "/internal/trade/top-sold");
            var response = new MockHttpServletResponse();
            new TraceIdFilter().doFilter(request, response, (req, res) -> {
                request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/internal/trade/top-sold");
                response.setStatus(422);
            });
            assertThat(logs.list).hasSize(1);
            assertThat(logs.list.getFirst().getKeyValuePairs()).anySatisfy(pair -> {
                assertThat(pair.key).isEqualTo("status"); assertThat(pair.value).isEqualTo(422);
            });
        } finally { logger.detachAppender(logs); MDC.clear(); }
    }
}
