package edu.czu.cinema.common.util;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import edu.czu.cinema.observe.RequestLog;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 请求级 traceId 过滤器：为每条请求生成短 traceId 写入 MDC，
 * 业务日志与异常响应携带同一 ID，排障时可把客户端报错与服务端日志对上。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse resp,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = req.getHeader("X-Trace-Id");
        if (traceId == null || traceId.isBlank() || traceId.length() > 64) {
            traceId = UUID.randomUUID().toString().substring(0, 8);
        }
        MDC.put(TRACE_ID_KEY, traceId);
        resp.setHeader("X-Trace-Id", traceId);
        RequestLog observation = (req.getRequestURI().startsWith("/api/") || req.getRequestURI().startsWith("/internal/"))
                ? RequestLog.start(traceId, req.getMethod()) : null;
        boolean failed = false;
        try {
            chain.doFilter(req, resp);
        } catch (IOException | ServletException | RuntimeException | Error failure) {
            failed = true;
            throw failure;
        } finally {
            if (observation != null) {
                Object mapped = req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                String route = mapped instanceof String pattern ? pattern : "unmapped";
                if (req.isAsyncStarted() && !failed) {
                    AtomicReference<String> outcome = new AtomicReference<>("complete");
                    AsyncListener listener = new AsyncListener() {
                        @Override public void onComplete(AsyncEvent event) { observation.complete(route, resp.getStatus(), outcome.get()); }
                        @Override public void onTimeout(AsyncEvent event) { outcome.set("timeout"); }
                        @Override public void onError(AsyncEvent event) { outcome.set("error"); }
                        @Override public void onStartAsync(AsyncEvent event) { event.getAsyncContext().addListener(this); }
                    };
                    try { req.getAsyncContext().addListener(listener); }
                    catch (IllegalStateException alreadyCompleted) { observation.complete(route, resp.getStatus(), "complete"); }
                } else {
                    observation.complete(route, failed ? 500 : resp.getStatus(), failed ? "error" : "complete");
                }
            }
            MDC.remove(TRACE_ID_KEY);
        }
    }
}
