package edu.czu.cinema.common.util;

import edu.czu.cinema.common.api.ResultCode;
import edu.czu.cinema.common.exception.BusinessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Spring Security 上下文快捷工具.
 */
public final class SecurityUtils {

    private SecurityUtils() {}

    public static Authentication currentAuthentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    public static Long requireUserId() {
        Long id = ContextHolder.currentId();
        if (id == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return id;
    }

    public static Long requireAdminId() {
        ContextHolder.Principal p = ContextHolder.current();
        if (p == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        if (!"ADMIN".equalsIgnoreCase(p.role())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "仅管理员可访问");
        }
        return p.id();
    }
}
