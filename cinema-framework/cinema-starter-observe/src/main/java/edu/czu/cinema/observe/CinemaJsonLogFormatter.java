package edu.czu.cinema.observe;

import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.skywalking.apm.toolkit.log.logback.v1.x.LogbackPatternConverter;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLogFormatter;
import org.springframework.core.env.Environment;

/** One-line JSON with a fixed field set; no automatic MDC, headers or argument serialization. */
public final class CinemaJsonLogFormatter implements StructuredLogFormatter<ILoggingEvent> {
    private static final List<String> REQUEST_FIELDS = List.of("event", "method", "route", "status", "outcome", "duration_ms", "order_id", "xid");
    private final LogbackPatternConverter traceConverter = new LogbackPatternConverter();
    private final JsonWriter<ILoggingEvent> writer;

    public CinemaJsonLogFormatter(Environment environment, ThrowableProxyConverter throwable) {
        List<String> secrets = new ArrayList<>();
        System.getenv().forEach((key, value) -> { if (isSecretName(key)) secrets.add(value); });
        for (String name : List.of("JWT_SECRET", "INTERNAL_SERVICE_KEY", "INITIAL_ADMIN_PASSWORD", "DASHSCOPE_API_KEY", "ALIYUN_OSS_AK", "ALIYUN_OSS_SK")) {
            secrets.add(environment.getProperty(name));
        }
        LogValueRedactor redactor = new LogValueRedactor(secrets);
        String service = environment.getProperty("spring.application.name", "cinema");
        writer = JsonWriter.<ILoggingEvent>of(members -> {
            members.add("@timestamp", event -> event.getInstant().toString());
            members.add("service", service);
            members.add("level", event -> event.getLevel().toString());
            members.add("logger", ILoggingEvent::getLoggerName);
            members.add("thread", ILoggingEvent::getThreadName);
            members.add("message", event -> redactor.redact(event.getFormattedMessage()));
            members.add("trace_id", event -> RequestLog.normalizeTraceId(stringField(event, "trace_id", traceConverter.convert(event))));
            members.add("request_id", event -> redactor.redact(stringField(event, "request_id", event.getMDCPropertyMap().getOrDefault("traceId", ""))));
            for (String field : REQUEST_FIELDS) {
                members.add(field, event -> safeField(event, field, redactor));
            }
            members.add("exception", event -> redactor.redact(throwable.convert(event)));
        }).withNewLineAtEnd();
    }

    private static boolean isSecretName(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.contains("PASSWORD") || upper.contains("SECRET") || upper.contains("TOKEN")
            || upper.endsWith("_KEY") || upper.endsWith("_AK") || upper.endsWith("_SK");
    }

    private static Object field(ILoggingEvent event, String key) {
        if (event.getKeyValuePairs() != null) {
            for (var pair : event.getKeyValuePairs()) if (pair.key.equals(key)) return pair.value;
        }
        return null;
    }

    private static String stringField(ILoggingEvent event, String key, String fallback) {
        Object value = field(event, key);
        return value instanceof String text ? text : fallback;
    }

    private static Object safeField(ILoggingEvent event, String key, LogValueRedactor redactor) {
        Object value = field(event, key);
        if (value instanceof Number || value instanceof Boolean) return value;
        return value instanceof String text ? redactor.redact(text) : null;
    }

    @Override public String format(ILoggingEvent event) { return writer.writeToString(event); }
}
