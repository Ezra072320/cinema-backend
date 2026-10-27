package edu.czu.cinema.observe;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.skywalking.apm.toolkit.trace.TraceContext;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;
import org.springframework.mock.env.MockEnvironment;

class CinemaLoggingTest {
    private CinemaJsonLogFormatter formatter() {
        var throwable = new ThrowableProxyConverter();
        throwable.start();
        return new CinemaJsonLogFormatter(new MockEnvironment().withProperty("spring.application.name", "test-cinema")
                .withProperty("JWT_SECRET", "private-configured-secret"), throwable);
    }

    private LoggingEvent event(String message) {
        var context = new LoggerContext();
        var event = new LoggingEvent("test", context.getLogger("test"), Level.INFO, message, null, null);
        event.setMDCPropertyMap(Map.of("traceId", "req-42", "Authorization", "must-not-serialize"));
        return event;
    }

    @Test void structuredOutputEscapesLinesAndWhitelistsContextWithoutRequiringAgent() throws Exception {
        var event = event("中文 \"quoted\"\nline two");
        event.setKeyValuePairs(List.of(new KeyValuePair("password", new Object() {
            @Override public String toString() { throw new AssertionError("Unapproved key serialized"); }
        })));
        String output = formatter().format(event);
        var json = new ObjectMapper().readTree(output);
        assertThat(output.lines()).hasSize(1);
        assertThat(json.path("message").asText()).isEqualTo("中文 \"quoted\"\nline two");
        assertThat(json.path("trace_id").asText()).isEmpty();
        assertThat(json.path("request_id").asText()).isEqualTo("req-42");
        assertThat(output).doesNotContain("must-not-serialize", "password");
    }

    @Test void finalOutputMasksKnownCredentialsJwtNamedSecretsAndUrlQueries() throws Exception {
        var event = event("configured private-configured-secret password=short-value JWT eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature https://user:pass@example.org/path?prompt=private-query#fragment");
        String output = formatter().format(event);
        assertThat(output).doesNotContain("private-configured-secret", "short-value", "eyJhbGci", "private-query", "user:pass", "#fragment");
        assertThat(new ObjectMapper().readTree(output).path("message").asText()).contains("[redacted]", "https://example.org/path");
    }

    @Test void capturedTraceSurvivesCallbackThreadAndCompletionOnlyLogsOnce() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestLog.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            RequestLog request;
            try (var toolkit = mockStatic(TraceContext.class)) {
                toolkit.when(TraceContext::traceId).thenReturn("sampled-real-trace-42");
                request = RequestLog.start("req-42", "POST");
            }
            CompletableFuture.runAsync(() -> request.complete("/api/ai/chat", 200, "complete")).join();
            request.complete("/api/ai/chat", 500, "error");
            assertThat(appender.list).hasSize(1);
            var json = new ObjectMapper().readTree(formatter().format(appender.list.getFirst()));
            assertThat(json.path("trace_id").asText()).isEqualTo("sampled-real-trace-42");
            assertThat(json.path("status").asInt()).isEqualTo(200);
            assertThat(json.path("route").asText()).isEqualTo("/api/ai/chat");
        } finally { logger.detachAppender(appender); }
    }
}
