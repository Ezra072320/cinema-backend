package edu.czu.cinema.common.util;

import java.util.HashMap;
import java.util.Map;

/**
 * 替代 ThreadLocal 直接暴露的可读写上下文.
 * <p>
 * 沿袭原项目"登录拦截器 -> ThreadLocal 注入当前用户"的思路，但更显式：
 * 任何业务代码通过 {@link #current()} 获取当前登录态（id/role/name）.
 * </p>
 */
public final class ContextHolder {

    public record Principal(Long id, String name, String role) {}

    private static final ThreadLocal<Principal> HOLDER = new ThreadLocal<>();

    private ContextHolder() {
    }

    public static void set(Principal principal) {
        HOLDER.set(principal);
    }

    public static Principal current() {
        return HOLDER.get();
    }

    public static Long currentId() {
        Principal p = HOLDER.get();
        return p == null ? null : p.id();
    }

    public static String currentRole() {
        Principal p = HOLDER.get();
        return p == null ? null : p.role();
    }

    public static void clear() {
        HOLDER.remove();
    }

    /** 把 JWT 解析出的 Map claims 转 Principal. */
    public static Principal fromClaims(Map<String, Object> claims) {
        if (claims == null) {
            return null;
        }
        Object id = claims.get("id");
        Object name = claims.get("name");
        Object role = claims.get("role");
        return new Principal(
                id == null ? null : Long.parseLong(id.toString()),
                name == null ? null : name.toString(),
                role == null ? null : role.toString()
        );
    }

    /** 把 Principal 反向写出 claims，方便写 Token. */
    public static Map<String, Object> toClaims(Principal principal) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", principal.id());
        m.put("name", principal.name());
        m.put("role", principal.role());
        return m;
    }
}
