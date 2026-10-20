package edu.czu.cinema.security;

import edu.czu.cinema.common.util.PasswordUtil;
import edu.czu.cinema.domain.admin.entity.Admin;
import edu.czu.cinema.domain.admin.mapper.AdminMapper;
import edu.czu.cinema.domain.user.entity.User;
import edu.czu.cinema.domain.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 令牌时效内的账号状态复核.
 * <p>
 * JWT 无状态且默认 12 小时有效——冻结、降级、改密若只在登录时校验，
 * 失效动作最长 12 小时后才生效。此守卫在每个请求解析令牌后按主键回查
 * 账号现状：status 必须为启用，且令牌里的 {@code pv}（口令版本指纹，
 * 见 {@link PasswordUtil#version}）必须与当前口令哈希一致。改密后旧令牌
 * 立即失效；冻结后请求立即被拒。单影院量级下每请求一次主键查询可接受。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountStateGuard {

    private final UserMapper userMapper;
    private final AdminMapper adminMapper;

    /**
     * @param role        USER / ADMIN
     * @param id          账号 id
     * @param pwdVersion  令牌中的 pv 声明（签发时为 PasswordUtil.version(哈希)）
     * @return false 表示账号已冻结/删除/改密，令牌应被拒绝
     */
    public boolean isActive(String role, Long id, String pwdVersion) {
        if (id == null || role == null) {
            return false;
        }
        try {
            switch (role.toUpperCase()) {
                case "ADMIN" -> {
                    Admin admin = adminMapper.selectById(id);
                    return admin != null
                            && admin.getStatus() != null && admin.getStatus() == 1
                            && pwdVersionMatches(pwdVersion, admin.getPassword());
                }
                case "USER" -> {
                    User user = userMapper.selectById(id);
                    return user != null
                            && user.getStatus() != null && user.getStatus() == 1
                            && pwdVersionMatches(pwdVersion, user.getPassword());
                }
                default -> {
                    return false;
                }
            }
        } catch (Exception e) {
            // 复核查询失败时 fail-closed：宁可要求重新登录，不放行无法核实的令牌
            log.warn("账号状态复核失败 role={} id={}", role, id, e);
            return false;
        }
    }

    private boolean pwdVersionMatches(String claimPv, String currentHash) {
        // 无 pv 声明的旧令牌直接拒绝（历史令牌不带改密失效能力）
        return claimPv != null && claimPv.equals(PasswordUtil.version(currentHash));
    }
}
