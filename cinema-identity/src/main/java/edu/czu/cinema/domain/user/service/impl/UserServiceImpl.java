package edu.czu.cinema.domain.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import edu.czu.cinema.common.api.PageResult;
import edu.czu.cinema.common.api.ResultCode;
import edu.czu.cinema.common.exception.BusinessException;
import edu.czu.cinema.common.util.PasswordUtil;
import edu.czu.cinema.domain.user.dto.UserUpdateDTO;
import edu.czu.cinema.domain.user.entity.User;
import edu.czu.cinema.domain.user.mapper.UserMapper;
import edu.czu.cinema.domain.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;

    @Override
    public User getByName(String name) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getName, name));
    }

    @Override
    public User getById(Long id) {
        return id == null ? null : userMapper.selectById(id);
    }

    @Override
    public User login(String name, String password) {
        User u = getByName(name);
        if (u == null || !PasswordUtil.matches(password, u.getPassword())) {
            throw new BusinessException(ResultCode.USER_PASSWORD_ERROR);
        }
        if (u.getStatus() != null && u.getStatus() == 0) {
            throw new BusinessException(ResultCode.USER_DISABLED);
        }
        // on-login rehash：历史 MD5 哈希校验通过后立刻重算 BCrypt 写回，
        // 老用户口令零感知完成迁移；此后 PasswordUtil.matches 只走 BCrypt 分支
        if (PasswordUtil.needsUpgrade(u.getPassword())) {
            u.setPassword(PasswordUtil.encode(password));
        }
        u.setLastAccessTime(LocalDateTime.now());
        userMapper.updateById(u);
        return u;
    }

    @Override
    @Transactional
    public User register(String name, String password, Integer age) {
        if (getByName(name) != null) {
            throw new BusinessException(ResultCode.USER_ALREADY_EXISTS);
        }
        User u = new User();
        u.setName(name);
        // 新口令只写 BCrypt；旧 md5() 写入路径已删除（P1-S2）
        u.setPassword(PasswordUtil.encode(password));
        u.setAge(age == null ? 0 : age);
        u.setPic("https://cube.elemecdn.com/3/7c/3ea6beec64369c2642b92c6726f1epng.png");
        u.setStatus(1);
        u.setCreateTime(LocalDateTime.now());
        try {
            userMapper.insert(u);
        } catch (DuplicateKeyException e) {
            // name 有唯一索引：并发注册同名时上面的预查拦不住，这里兜底成业务异常而不是 500
            throw new BusinessException(ResultCode.USER_ALREADY_EXISTS);
        }
        return u;
    }

    @Override
    @Transactional
    public void updateCurrent(Long id, UserUpdateDTO dto) {
        User pre = userMapper.selectById(id);
        if (pre == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        // 自身更新白名单只有 name/age：pic 走 modAvatar、status 不可自助写，
        // 避免「更新资料」顺带把自己解冻/篡改头像
        if (dto.name() != null && !dto.name().equals(pre.getName())) {
            if (getByName(dto.name()) != null) {
                throw new BusinessException("用户名已被占用");
            }
        }
        LambdaUpdateWrapper<User> upd = new LambdaUpdateWrapper<User>().eq(User::getId, id);
        boolean any = false;
        if (dto.name() != null) {
            upd.set(User::getName, dto.name());
            any = true;
        }
        if (dto.age() != null) {
            upd.set(User::getAge, dto.age());
            any = true;
        }
        if (any) {
            userMapper.update(null, upd);
        }
    }

    @Override
    @Transactional
    public boolean updateManaged(UserUpdateDTO dto) {
        if (dto == null || dto.id() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "用户 ID 不能为空");
        }
        User pre = userMapper.selectById(dto.id());
        if (pre == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        if (dto.name() != null && !dto.name().equals(pre.getName()) && getByName(dto.name()) != null) {
            throw new BusinessException("用户名已被占用");
        }
        if (dto.pic() != null) {
            requireHttpUrl(dto.pic());
        }
        if (dto.status() != null && dto.status() != 0 && dto.status() != 1) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "状态只能是 0（冻结）或 1（启用）");
        }
        // LambdaUpdateWrapper 逐字段白名单 set：口令/创建时间/主键永不进 SET 列表；
        // 也不走实体 updateById——实体字段默认值会把「未提供」写成 1（静默解冻）
        LambdaUpdateWrapper<User> upd = new LambdaUpdateWrapper<User>().eq(User::getId, dto.id());
        boolean any = false;
        if (dto.name() != null) {
            upd.set(User::getName, dto.name());
            any = true;
        }
        if (dto.age() != null) {
            upd.set(User::getAge, dto.age());
            any = true;
        }
        if (dto.pic() != null) {
            upd.set(User::getPic, dto.pic());
            any = true;
        }
        if (dto.status() != null) {
            upd.set(User::getStatus, dto.status());
            any = true;
        }
        return !any || userMapper.update(null, upd) > 0;
    }

    @Override
    @Transactional
    public void resetPassword(Long id, String oldPwd, String newPwd, String confirm) {
        User u = userMapper.selectById(id);
        if (u == null || !PasswordUtil.matches(oldPwd, u.getPassword())) {
            throw new BusinessException("原密码不正确");
        }
        // 服务端 null 防护：不依赖 @NotBlank（AI/内部调用不走 Bean Validation）
        if (newPwd == null || confirm == null || !newPwd.equals(confirm)) {
            throw new BusinessException("两次输入的新密码不一致");
        }
        // 口令只写 BCrypt；哈希变化使旧令牌的 pv 声明失配，改密即刻全端下线（P1-S2）
        u.setPassword(PasswordUtil.encode(newPwd));
        userMapper.updateById(u);
    }

    @Override
    @Transactional
    public void changeAvatar(Long id, String url) {
        User u = userMapper.selectById(id);
        if (u == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        requireHttpUrl(url);
        userMapper.update(null, new LambdaUpdateWrapper<User>().eq(User::getId, id).set(User::getPic, url));
    }

    @Override
    @Transactional
    public boolean modifyStatus(String name, int status) {
        requireValidStatus(status);
        User u = getByName(name);
        if (u == null) {
            return false;
        }
        return userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, u.getId()).set(User::getStatus, status)) > 0;
    }

    @Override
    @Transactional
    public boolean modifyStatus(Long id, int status) {
        if (id == null) return false;
        requireValidStatus(status);
        User u = userMapper.selectById(id);
        if (u == null) return false;
        return userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, id).set(User::getStatus, status)) > 0;
    }

    @Override
    @Transactional
    public boolean removeById(Long id) {
        if (id == null) return false;
        return userMapper.deleteById(id) > 0;
    }

    @Override
    public PageResult<User> page(int page, int size, String name, Integer status) {
        if (page < 1 || size < 1 || size > 100) {
            // 与 ReviewQueryService.validatePage 同风格：非法分页先挡掉，避免深分页拖库
            throw new BusinessException("页码或每页数量不合法");
        }
        Page<User> req = Page.of(page, size);
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>()
                .like(name != null && !name.isBlank(), User::getName, escapeLike(name))
                .eq(status != null && status != 2, User::getStatus, status)
                .orderByDesc(User::getCreateTime);
        Page<User> result = userMapper.selectPage(req, wrapper);
        return PageResult.of(result.getTotal(), result.getRecords());
    }

    /** LIKE 关键字转义：用户输入的 %/_ 本身是通配符，不转义会被当通配符改变查询语义. */
    private static String escapeLike(String kw) {
        if (kw == null || kw.isBlank()) {
            return kw;
        }
        return kw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** 头像/图片 URL 只接受 http(s)，顺带拦掉 javascript: 等伪协议注入. */
    private static void requireHttpUrl(String url) {
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "图片地址必须以 http:// 或 https:// 开头");
        }
    }

    private static void requireValidStatus(int status) {
        if (status != 0 && status != 1) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "状态只能是 0（冻结）或 1（启用）");
        }
    }
}
