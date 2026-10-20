package edu.czu.cinema.domain.user;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisMapperBuilderAssistant;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import edu.czu.cinema.common.exception.BusinessException;
import edu.czu.cinema.domain.user.dto.UserUpdateDTO;
import edu.czu.cinema.domain.user.entity.User;
import edu.czu.cinema.domain.user.mapper.UserMapper;
import edu.czu.cinema.domain.user.service.impl.UserServiceImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/** 管理端编辑用户白名单（P1-S4）：口令/创建时间不可写，status 不传不改（杜绝静默解冻）. */
class UserManagedEditTest {

    private final UserMapper userMapper = mock(UserMapper.class);
    private final UserServiceImpl service = new UserServiceImpl(userMapper);

    /** 纯单测没有 Spring 容器，Lambda 列名解析需要先注册 TableInfo. */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MybatisMapperBuilderAssistant(new MybatisConfiguration(), "user-edit-test"), User.class);
    }

    private void seed(long id, int status) {
        User u = new User();
        u.setId(id);
        u.setName("旧名");
        u.setPassword("$2a$10$xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
        u.setStatus(status);
        when(userMapper.selectById(id)).thenReturn(u);
        when(userMapper.update(any(), any())).thenReturn(1);
    }

    @Test
    void editOnlyNamePutsNameIntoSqlSetAndNeverPasswordOrStatus() {
        seed(1L, 0);
        boolean ok = service.updateManaged(new UserUpdateDTO(1L, "新名", null, null, null));

        assertThat(ok).isTrue();
        LambdaUpdateWrapper<User> w = captureUpdateWrapper();
        assertThat(w.getSqlSet()).contains("name").doesNotContain("password")
                .doesNotContain("status").doesNotContain("create_time");
        // 口径：白名单走 update(wrapper)，绝不回退到实体 updateById（那会把整实体塞进 SET）
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    void editWithStatusNullDoesNotSilentlyUnfreeze() {
        seed(1L, 0); // 当前为冻结
        service.updateManaged(new UserUpdateDTO(1L, "新名", null, null, null));
        LambdaUpdateWrapper<User> w = captureUpdateWrapper();
        // status 未提供，不得写入 —— 冻结用户不会被「编辑资料」顺手解冻
        assertThat(w.getSqlSet()).doesNotContain("status");
    }

    @Test
    void editWithValidStatus0And1IsAccepted() {
        seed(1L, 1);
        service.updateManaged(new UserUpdateDTO(1L, "名", null, null, 0));
        LambdaUpdateWrapper<User> w0 = captureUpdateWrapper();
        assertThat(w0.getSqlSet()).contains("status");

        reset(userMapper);
        seed(1L, 0);
        service.updateManaged(new UserUpdateDTO(1L, "名", null, null, 1));
        assertThat(captureUpdateWrapper().getSqlSet()).contains("status");
    }

    @Test
    void editWithOutOfRangeStatusIsRejected() {
        seed(1L, 1);
        assertThatThrownBy(() -> service.updateManaged(new UserUpdateDTO(1L, "名", null, null, 99)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("状态只能是");
    }

    @Test
    void editPicMustBeHttpUrl() {
        seed(1L, 1);
        assertThatThrownBy(() -> service.updateManaged(new UserUpdateDTO(1L, null, null, "javascript:alert(1)", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("http");
    }

    @Test
    void editMissingIdIsRejected() {
        assertThatThrownBy(() -> service.updateManaged(new UserUpdateDTO(null, "名", null, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ID 不能为空");
    }

    @Test
    void editDuplicateNameIsRejected() {
        seed(1L, 1);
        User other = new User();
        other.setId(9L);
        other.setName("已被占用");
        when(userMapper.selectOne(any())).thenReturn(other);
        assertThatThrownBy(() -> service.updateManaged(new UserUpdateDTO(1L, "已被占用", null, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("占用");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private LambdaUpdateWrapper<User> captureUpdateWrapper() {
        org.mockito.ArgumentCaptor<Wrapper> captor = org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        verify(userMapper, atLeastOnce()).update(isNull(), captor.capture());
        return (LambdaUpdateWrapper<User>) captor.getValue();
    }
}