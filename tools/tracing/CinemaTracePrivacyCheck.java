package cinema.observe;

import java.lang.reflect.Field;
import java.util.List;
import org.apache.skywalking.apm.agent.core.context.TracingContext;
import org.apache.skywalking.apm.agent.core.context.TracingContextListener;
import org.apache.skywalking.apm.agent.core.context.trace.AbstractTracingSpan;
import org.apache.skywalking.apm.agent.core.context.trace.LocalSpan;
import org.apache.skywalking.apm.agent.core.context.trace.TraceSegment;

/** Executed by init-tracing.py against the exact downloaded agent before startup. */
public final class CinemaTracePrivacyCheck {
    public static void main(String[] args) throws Exception {
        CinemaTracePrivacy privacy = new CinemaTracePrivacy();
        check(CinemaTracePrivacy.cleanUrl("https://u:secret@example.org:8443/a?token=hidden#private")
              .equals("https://example.org:8443/a"), "authority/query/fragment stripping");
        check(CinemaTracePrivacy.cleanUrl("/电影?q=hidden").equals("/电影"), "relative Unicode URL");
        check(CinemaTracePrivacy.cleanUrl("http://[::1]:8080/a?x=hidden").equals("http://[::1]:8080/a"), "IPv6 URL");
        check(CinemaTracePrivacy.cleanUrl("http://[bad?secret").equals("[redacted-url]"), "malformed URL");
        check(CinemaTracePrivacy.cleanUrl("http://example.org/%E7%94%B5%E5%BD%B1?q=x")
              .equals("http://example.org/%E7%94%B5%E5%BD%B1"), "encoded URL");
        LocalSpan span = new LocalSpan(3, 1, "GET:/movie", null);
        span.tag("url", "http://example.org/movie?password=hidden");
        span.tag("http.params", "password=hidden");
        span.tag("db.sql.parameters", "hidden");
        span.tag("http.method", "GET");
        TraceSegment segment = new TraceSegment();
        segment.archive(span);
        // Match the reporter's lifecycle: privacy in prepare, reporter in onComplete.
        privacy.prepare();
        boolean[] reported = {false};
        TracingContextListener reporter = finished -> {
            var object = span.transform().build();
            check(!object.toString().contains("hidden"), "privacy before serialization/queueing");
            check(object.getSpanId() == 3 && object.getParentSpanId() == 1, "span links preserved");
            check(object.getOperationName().equals("GET:/movie"), "endpoint preserved");
            check(object.getTagsList().stream().anyMatch(t -> t.getKey().equals("http.method") && t.getValue().equals("GET")), "method preserved");
            reported[0] = true;
        };
        TracingContext.ListenerManager.add(reporter);
        try {
            var notify = TracingContext.ListenerManager.class.getDeclaredMethod("notifyFinish", TraceSegment.class);
            notify.setAccessible(true);
            notify.invoke(null, segment);
            check(reported[0] && !segment.isIgnore(), "reporter called after sanitization");
        } finally {
            TracingContext.ListenerManager.remove(reporter);
            privacy.shutdown();
        }
        Field spans = TraceSegment.class.getDeclaredField("spans");
        spans.setAccessible(true);
        spans.set(segment, List.of(new Object()));
        privacy.afterFinished(segment);
        check(segment.isIgnore(), "corrupt segment dropped instead of leaking values");
        System.out.println("Pinned SkyWalking privacy ABI, URL cases, reporter order and fail-closed checks passed.");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
