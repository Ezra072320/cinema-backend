package edu.czu.cinema.domain.admin;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisMapperBuilderAssistant;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import edu.czu.cinema.common.exception.BusinessException;
import edu.czu.cinema.common.util.ContextHolder;
import edu.czu.cinema.common.util.PasswordUtil;
import edu.czu.cinema.domain.admin.dto.AdminAddDTO;
import edu.czu.cinema.domain.admin.entity.Admin;
import edu.czu.cinema.domain.admin.mapper.AdminMapper;
import edu.czu.cinema.domain.admin.service.impl.AdminServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/** 管理员保护（P1-R5）：禁冻自己、保至少一名启用管理员、status 白名单；口令 BCrypt. */
class AdminServiceGuardTest {

    private final AdminMapper adminMapper = mock(AdminMapper.class);
    private final AdminServiceImpl service = new AdminServiceImpl(adminMapper);

    /** 纯单测没有 Spring 容器，Lambda 列名解析需要先注册 TableInfo. */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MybatisMapperBuilderAssistant(new MybatisConfiguration(), "admin-guard-test"), Admin.class);
    }

    @AfterEach
    void tearDown() {
        ContextHolder.clear();
    }

    private Admin admin(long id, String name, int status) {
        Admin a = new Admin();
        a.setId(id);
        a.setName(name);
        a.setPassword(PasswordUtil.encode("pwd123"));
        a.setStatus(status);
        return a;
    }

    @Test
    void cannotFreezeSelf() {
        ContextHolder.set(new ContextHolder.Principal(1L, "root", "ADMIN"));
        when(adminMapper.selectOne(any())).thenReturn(admin(1L, "root", 1));

        assertThatThrownBy(() -> service.modifyStatus("root", 0))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("当前登录");
        verify(adminMapper, never()).update(any(), any());
    }

    @Test
    void cannotFreezeLastEnabledAdmin() {
        ContextHolder.set(new ContextHolder.Principal(1L, "root", "ADMIN"));
        when(adminMapper.selectOne(any())).thenReturn(admin(9L, "other", 1));
        // 目标之外没有其他 status=1 管理员
        when(adminMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        assertThatThrownBy(() -> service.modifyStatus("other", 0))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("至少保留一名启用管理员");
        verify(adminMapper, never()).update(any(), any());
    }

    @Test
    void freezeAllowedWhenAnotherEnabledAdminExists() {
        ContextHolder.set(new ContextHolder.Principal(1L, "root", "ADMIN"));
        when(adminMapper.selectOne(any())).thenReturn(admin(9L, "other", 1));
        when(adminMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        when(adminMapper.update(any(), any())).thenReturn(1);

        boolean ok = service.modifyStatus("other", 0);

        assertThat(ok).isTrue();
        verify(adminMapper).update(any(), any());
    }

    @Test
    void outOfRangeStatusIsRejected() {
        assertThatThrownBy(() -> service.modifyStatus("any", 99))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("状态只能是");
    }

    @Test
    void addAdminWritesBcrypt() {
        when(adminMapper.selectOne(any())).thenReturn(null);
        when(adminMapper.insert(any(Admin.class))).thenReturn(1);

        Admin a = service.add(new AdminAddDTO("新管理员", "pass123", 30));

        assertThat(PasswordUtil.needsUpgrade(a.getPassword())).isFalse();
        assertThat(PasswordUtil.matches("pass123", a.getPassword())).isTrue();
    }

    @Test
    void loginWithLegacyMd5RehashesToBcrypt() {
        Admin legacy = admin(1L, "root", 1);
        legacy.setPassword(PasswordUtil.md5("oldpass")); // 历史固定 salt MD5
        when(adminMapper.selectOne(any())).thenReturn(legacy);

        Admin logged = service.login("root", "oldpass");

        assertThat(PasswordUtil.needsUpgrade(logged.getPassword())).isFalse();
        assertThat(PasswordUtil.matches("oldpass", logged.getPassword())).isTrue();
        verify(adminMapper).updateById(argThat((Admin a) -> !PasswordUtil.needsUpgrade(a.getPassword())));
    }

    @Test
    void changePasswordRejectsBlank() {
        when(adminMapper.selectById(1L)).thenReturn(admin(1L, "root", 1));
        assertThatThrownBy(() -> service.changePassword(1L, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("新密码不能为空");
        when(adminMapper.selectById(2L)).thenReturn(admin(2L, "b", 1));
        assertThatThrownBy(() -> service.changePassword(2L, "  "))
                .isInstanceOf(BusinessException.class);
    }
}