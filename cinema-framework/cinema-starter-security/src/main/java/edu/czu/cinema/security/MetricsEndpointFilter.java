package edu.czu.cinema.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/** Machine metrics use an independent credential, without a user JWT or Identity lookup. */
public final class MetricsEndpointFilter extends OncePerRequestFilter {
    private final boolean enabled;
    private final byte[] expected;

    public MetricsEndpointFilter(boolean enabled,String key) {
        if (enabled && (key==null || key.length()<32)) throw new IllegalArgumentException("Metrics key must contain at least 32 characters");
        this.enabled=enabled;
        this.expected=key==null ? new byte[0] : key.getBytes(StandardCharsets.UTF_8);
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path=UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        return !path.equals("/actuator/prometheus") && !path.startsWith("/actuator/prometheus/");
    }

    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws IOException,ServletException {
        response.setHeader("Cache-Control","no-store");
        if (!enabled) { response.setStatus(404); return; }
        String supplied=request.getHeader("X-Metrics-Key");
        if (supplied==null || !MessageDigest.isEqual(expected,supplied.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(401);
            return;
        }
        chain.doFilter(request,response);
    }
}
