package edu.czu.cinema.security;

import edu.czu.cinema.common.util.ContextHolder;
import edu.czu.cinema.common.util.JwtUtil;

import io.jsonwebtoken.Claims;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * JWT 鉴权过滤器.
 *
 * <p>替代原 {@code LoginInterceptor}：把解析结果写入 Spring Security 上下文、 同时写入 {@link
 * ContextHolder}，让业务层两种风格都能使用.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final AccountStateVerifier accountStateGuard;

    @Override
    protected void doFilterInternal(
            HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String token = resolveToken(req);
        if (token != null) {
            try {
                Claims claims = jwtUtil.parse(token);
                Map<String, Object> map = claims;
                ContextHolder.Principal principal = ContextHolder.fromClaims(map);
                if (principal != null) {
                    // 时效内复核账号状态与口令版本：冻结/改密立即生效，不等令牌过期
                    Object pv = map.get("pv");
                    if (!accountStateGuard.isActive(
                            principal.role(),
                            principal.id(),
                            pv == null ? null : String.valueOf(pv))) {
                        log.debug("令牌已被拒绝：账号冻结/改密/不存在 id={}", principal.id());
                    } else {
                        ContextHolder.set(principal);
                        String role = principal.role();
                        Collection<SimpleGrantedAuthority> authorities =
                                role == null
                                        ? List.of()
                                        : List.of(
                                                new SimpleGrantedAuthority(
                                                        "ROLE_" + role.toUpperCase()));
                        UsernamePasswordAuthenticationToken auth =
                                new UsernamePasswordAuthenticationToken(
                                        principal, token, authorities);
                        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(req));
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    }
                }
            } catch (edu.czu.cinema.common.exception.ServiceUnavailableException unavailable) {
                resp.setStatus(503);
                resp.setCharacterEncoding("UTF-8");
                resp.setContentType("application/json");
                resp.setHeader("Retry-After", "2");
                resp.getWriter()
                        .write(
                                """
                                {"code":3001,"msg":"账号服务暂不可用，请稍后重试"}
                                """);
                return;
            } catch (Exception ignored) {
                // token 无效时不写入上下文，由后续 Security 决策
            }
        }
        try {
            chain.doFilter(req, resp);
        } finally {
            ContextHolder.clear();
        }
    }

    private String resolveToken(HttpServletRequest req) {
        String bearer = req.getHeader("Authorization");
        if (bearer == null || bearer.isBlank()) {
            return null;
        }
        String t = bearer.trim();
        return t.startsWith("Bearer ") ? t.substring(7) : t;
    }
}
