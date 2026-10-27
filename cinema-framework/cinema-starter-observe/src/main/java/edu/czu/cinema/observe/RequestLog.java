package edu.czu.cinema.observe;

import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.skywalking.apm.toolkit.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Captures tracing on the request thread, then safely completes on any callback thread. */
public final class RequestLog {
    private static final Logger LOG = LoggerFactory.getLogger(RequestLog.class);
    private final long started = System.nanoTime();
    private final String traceId;
    private final String requestId;
    private final String method;
    private final AtomicBoolean completed = new AtomicBoolean();

    private RequestLog(String requestId, String method) {
        this.traceId = normalizeTraceId(TraceContext.traceId());
        this.requestId = requestId;
        this.method = method;
    }

    public static RequestLog start(String requestId, String method) {
        return new RequestLog(requestId, method);
    }

    public void complete(String route, int status, String outcome) {
        if (!completed.compareAndSet(false, true)) return;
        LOG.atInfo().addKeyValue("event", "http.request")
            .addKeyValue("trace_id", traceId).addKeyValue("request_id", requestId)
            .addKeyValue("method", method).addKeyValue("route", route)
            .addKeyValue("status", status).addKeyValue("outcome", outcome)
            .addKeyValue("duration_ms", (System.nanoTime() - started) / 1_000_000L)
            .log("HTTP request completed");
    }

    static String normalizeTraceId(String value) {
        if (value == null) return "";
        value = value.trim();
        if (value.startsWith("TID:")) value = value.substring(4).trim();
        if (value.equals("N/A") || value.equals("Ignored_Trace")) return "";
        return value;
    }
}
