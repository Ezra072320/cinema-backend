package edu.czu.cinema.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
@Order(-110)
public class InternalServiceFilter extends OncePerRequestFilter {
    private final byte[] key;

    public InternalServiceFilter(@Value("${cinema.internal.key}") String key) {
        if (key.length() < 32)
            throw new IllegalStateException(
                    "INTERNAL_SERVICE_KEY must contain at least 32 characters");
        this.key = key.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        if (req.getRequestURI().startsWith("/internal/")) {
            String supplied = req.getHeader("X-Service-Key");
            if (supplied == null
                    || !MessageDigest.isEqual(key, supplied.getBytes(StandardCharsets.UTF_8))) {
                resp.sendError(403);
                return;
            }
        }
        chain.doFilter(req, resp);
    }
}
