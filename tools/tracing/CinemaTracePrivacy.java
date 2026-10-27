package cinema.observe;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.skywalking.apm.agent.core.boot.BootService;
import org.apache.skywalking.apm.agent.core.context.TracingContext;
import org.apache.skywalking.apm.agent.core.context.TracingContextListener;
import org.apache.skywalking.apm.agent.core.context.trace.AbstractTracingSpan;
import org.apache.skywalking.apm.agent.core.context.trace.TraceSegment;
import org.apache.skywalking.apm.agent.core.context.util.TagValuePair;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;

/** Agent-only extension, pinned to the verified SkyWalking 9.8.0 distribution.
 *
 * The stock WebFlux/Gateway/Feign plugins can include query strings in `url`
 * even when collect_http_params is false. Register in prepare(), before the
 * stock reporter registers in onComplete(), to sanitize before queueing.
 * Reflection is limited to two pinned agent fields; no application state is read.
 */
public final class CinemaTracePrivacy implements BootService, TracingContextListener {
    private static final Set<String> PAYLOAD_TAGS = Set.of(
        "http.params", "http.headers", "http.body", "http.request.body", "db.sql.parameters");
    private final Field spans = field(TraceSegment.class, "spans");
    private final Field tags = field(AbstractTracingSpan.class, "tags");
    private final AtomicBoolean failureReported = new AtomicBoolean();

    private static Field field(Class<?> type, String name) {
        try {
            Field result = type.getDeclaredField(name);
            result.setAccessible(true);
            return result;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unsupported SkyWalking privacy extension ABI", exception);
        }
    }

    @Override public void prepare() { TracingContext.ListenerManager.add(this); }
    @Override public void boot() { }
    @Override public void onComplete() { }
    @Override public void shutdown() { TracingContext.ListenerManager.remove(this); }

    @Override
    @SuppressWarnings("unchecked")
    public void afterFinished(TraceSegment segment) {
        try {
            for (AbstractTracingSpan span : (List<AbstractTracingSpan>) spans.get(segment)) {
                List<TagValuePair> values = (List<TagValuePair>) tags.get(span);
                if (values == null) continue;
                for (TagValuePair value : values) {
                    String key = value.getKey().key();
                    if (key.equals("url") || key.equals("http.url")) {
                        value.setValue(cleanUrl(value.getValue()));
                    } else if (PAYLOAD_TAGS.contains(key)) {
                        value.setValue("[redacted]");
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            // An incompatible/corrupt segment must never escape unsanitized.
            segment.setIgnore(true);
            if (failureReported.compareAndSet(false, true)) {
                LogManager.getLogger(CinemaTracePrivacy.class).warn(
                    "Cinema trace privacy dropped an incompatible segment: " + exception.getClass().getSimpleName());
            }
        }
    }

    static String cleanUrl(String value) {
        if (value == null) return "[redacted-url]";
        try {
            URI uri = new URI(value);
            if (uri.getScheme() == null && uri.getRawAuthority() == null) {
                return uri.getRawPath() == null ? "[redacted-url]" : uri.getRawPath();
            }
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) return "[redacted-url]";
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null).toASCIIString();
        } catch (URISyntaxException exception) {
            return "[redacted-url]";
        }
    }
}
