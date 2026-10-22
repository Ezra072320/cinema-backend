package edu.czu.cinema.common.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.Map;

/**
 * JWT 工具类.
 * <p>
 * 使用 jjwt 0.12.x 的新 API；密钥不足 32 字节直接拒绝启动，不做补零——
 * 补零会把短口令静默降级成弱密钥。
 * </p>
 */
@Slf4j
@Component
public class JwtUtil {

    /**
     * 历史默认值 —— 发现仍在使用则直接 fail-fast。
     * dev profile 有自己的本地默认串（仅用于开箱启动，prod 无默认值）；此常量拦截的是
     * 曾经作为全局兜底的旧值，避免遗留部署静默沿用它。
     */
    public static final String FORBIDDEN_DEFAULT_SECRET = "CZM_CINEMA_RENOVATION_SECRET_2026_PLEASE_CHANGE_ME";

    private final SecretKey key;
    private final long expireHours;

    public JwtUtil(@Value("${jwt.secret:}") String secret,
                   @Value("${jwt.expire-hours:12}") long expireHours) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("jwt.secret 未配置，请在环境变量 JWT_SECRET 设置至少 32 字节的随机串");
        }
        if (FORBIDDEN_DEFAULT_SECRET.equals(secret)) {
            throw new IllegalStateException("jwt.secret 仍在使用仓库历史默认串，禁止启动；通过 JWT_SECRET 注入真随机串");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            // jjwt 要求至少 256 bit；短密钥拒绝而非补零，防止弱密钥静默上线
            throw new IllegalStateException("jwt.secret 长度不足 32 字节（当前 " + bytes.length + "），请更换为至少 32 字节的随机串");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.expireHours = expireHours;
    }

    public String genToken(Map<String, Object> claims) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .claims(claims)
                .issuedAt(new Date(now))
                .expiration(new Date(now + Duration.ofHours(expireHours).toMillis()))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException e) {
            log.warn("token 解析失败：{}", e.getMessage());
            throw e;
        }
    }

    public Map<String, Object> parseToMap(String token) {
        return parse(token);
    }
}
