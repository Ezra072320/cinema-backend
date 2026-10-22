package edu.czu.cinema.domain.user;

import edu.czu.cinema.common.api.ResultCode;
import edu.czu.cinema.common.exception.BusinessException;
import edu.czu.cinema.common.util.PasswordUtil;
import edu.czu.cinema.domain.user.entity.User;
import edu.czu.cinema.domain.user.mapper.UserMapper;
import edu.czu.cinema.domain.user.service.impl.UserServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/** BCrypt 迁移（P1-S2）：新口令只写 BCrypt，旧 MD5 哈希登录时惰性重算. */
class UserPasswordMigrationTest {

    private final UserMapper userMapper = mock(UserMapper.class);
    private final UserServiceImpl service = new UserServiceImpl(userMapper);

    @Test
    void registerWritesBcryptHashNotLegacyMd5() {
        when(userMapper.selectOne(any())).thenReturn(null);
        User u = service.register("新人", "pass123", 20);
        assertThat(u.getPassword()).startsWith("$2").isNotEmpty();
        // 旧算法（固定 salt MD5）不满足 needsUpgrade=false，保证是 BCrypt
        assertThat(PasswordUtil.needsUpgrade(u.getPassword())).isFalse();
        assertThat(PasswordUtil.matches("pass123", u.getPassword())).isTrue();
    }

    @Test
    void loginWithLegacyMd5HashSucceedsAndRehashesToBcrypt() {
        User legacy = new User();
        legacy.setId(1L);
        legacy.setName("老王");
        legacy.setPassword(PasswordUtil.md5("oldpass")); // 历史固定 salt MD5
        legacy.setStatus(1);
        String legacyHash = legacy.getPassword(); // 登录会就地改写同一对象，先留旧值做断言
        when(userMapper.selectOne(any())).thenReturn(legacy);

        User logged = service.login("老王", "oldpass");

        assertThat(logged.getPassword()).startsWith("$2").isNotEqualTo(legacyHash);
        assertThat(PasswordUtil.needsUpgrade(logged.getPassword())).isFalse();
        assertThat(PasswordUtil.matches("oldpass", logged.getPassword())).isTrue();
        // 重算结果确实写回了（类型标注消歧：BaseMapper 还有 updateById(Collection) 重载）
        verify(userMapper).updateById(argThat((User u) -> !PasswordUtil.needsUpgrade(u.getPassword())));
    }

    @Test
    void loginWithBcryptHashDoesNotRewritePassword() {
        String bcrypt = PasswordUtil.encode("newpass");
        User u = new User();
        u.setId(2L);
        u.setName("小张");
        u.setPassword(bcrypt);
        u.setStatus(1);
        when(userMapper.selectOne(any())).thenReturn(u);

        User logged = service.login("小张", "newpass");

        assertThat(logged.getPassword()).isEqualTo(bcrypt); // 已是最新，不改写
        verify(userMapper, never()).updateById(argThat((User x) -> !x.getPassword().equals(bcrypt)));
    }

    @Test
    void concurrentRegisterSameNameMapsDuplicateKeyToBusinessException() {
        when(userMapper.selectOne(any())).thenReturn(null);
        doThrow(new DuplicateKeyException("dup name")).when(userMapper).insert(any(User.class));

        assertThatThrownBy(() -> service.register("重复", "pass123", 18))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ResultCode.USER_ALREADY_EXISTS.getCode()));
    }

    @Test
    void resetPasswordRejectsNullNewPassword() {
        User u = new User();
        u.setId(1L);
        u.setPassword(PasswordUtil.encode("oldpass"));
        when(userMapper.selectById(1L)).thenReturn(u);

        assertThatThrownBy(() -> service.resetPassword(1L, "oldpass", null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不一致");
        // 未执行任何写入
        verify(userMapper, never()).updateById(any(User.class));
    }
}