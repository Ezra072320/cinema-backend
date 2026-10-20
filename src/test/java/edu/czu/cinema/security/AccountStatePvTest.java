package edu.czu.cinema.security;

import edu.czu.cinema.common.util.PasswordUtil;
import edu.czu.cinema.domain.admin.entity.Admin;
import edu.czu.cinema.domain.admin.mapper.AdminMapper;
import edu.czu.cinema.domain.user.entity.User;
import edu.czu.cinema.domain.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 改密后旧令牌失效（AccountStateGuard + pv 声明）：token pv 与当前口令哈希指纹不符即拒. */
class AccountStatePvTest {

    private final UserMapper userMapper = mock(UserMapper.class);
    private final AdminMapper adminMapper = mock(AdminMapper.class);
    private final AccountStateGuard guard = new AccountStateGuard(userMapper, adminMapper);

    private User user(long id, String hash, int status) {
        User u = new User();
        u.setId(id);
        u.setPassword(hash);
        u.setStatus(status);
        return u;
    }

    @Test
    void activeUserWithMatchingPvIsPassed() {
        String hash = PasswordUtil.encode("pass123");
        when(userMapper.selectById(1L)).thenReturn(user(1L, hash, 1));

        assertThat(guard.isActive("USER", 1L, PasswordUtil.version(hash))).isTrue();
    }

    @Test
    void oldTokenAfterPasswordChangeIsRejected() {
        String before = PasswordUtil.encode("oldpass");
        String after = PasswordUtil.encode("newpass");
        // 签发令牌时的 pv 基于"改密前"的哈希
        String oldPv = PasswordUtil.version(before);
        // 改密后库里已是新哈希
        when(userMapper.selectById(1L)).thenReturn(user(1L, after, 1));

        // 旧令牌 pv 与新哈希指纹不一致 → 复核拒绝（等价 401，客户端需用新口令重登）
        assertThat(guard.isActive("USER", 1L, oldPv)).isFalse();
    }

    @Test
    void frozenUserIsRejectedEvenWithMatchingPv() {
        String hash = PasswordUtil.encode("pass123");
        when(userMapper.selectById(1L)).thenReturn(user(1L, hash, 0));

        assertThat(guard.isActive("USER", 1L, PasswordUtil.version(hash))).isFalse();
    }

    @Test
    void adminOldTokenAfterPasswordChangeIsRejected() {
        Admin before = new Admin();
        before.setId(1L);
        before.setPassword(PasswordUtil.encode("old"));
        before.setStatus(1);
        String oldPv = PasswordUtil.version(before.getPassword());

        Admin current = new Admin();
        current.setId(1L);
        current.setPassword(PasswordUtil.encode("new"));
        current.setStatus(1);
        when(adminMapper.selectById(1L)).thenReturn(current);

        assertThat(guard.isActive("ADMIN", 1L, oldPv)).isFalse();
    }
}