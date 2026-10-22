package edu.czu.cinema.domain.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import edu.czu.cinema.common.api.PageResult;
import edu.czu.cinema.common.api.ResultCode;
import edu.czu.cinema.common.exception.BusinessException;
import edu.czu.cinema.common.util.ContextHolder;
import edu.czu.cinema.common.util.PasswordUtil;
import edu.czu.cinema.domain.admin.dto.AdminAddDTO;
import edu.czu.cinema.domain.admin.entity.Admin;
import edu.czu.cinema.domain.admin.mapper.AdminMapper;
import edu.czu.cinema.domain.admin.service.AdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminServiceImpl implements AdminService {

    private final AdminMapper adminMapper;

    @Override
    public Admin login(String name, String password) {
        Admin a = getByName(name);
        if (a == null || !PasswordUtil.matches(password, a.getPassword())) {
            throw new BusinessException(ResultCode.ADMIN_PASSWORD_ERROR);
        }
        if (a.getStatus() != null && a.getStatus() == 0) {
            throw new BusinessException(ResultCode.FORBIDDEN, "管理员账号已被冻结");
        }
        // on-login rehash：老 MD5 哈希校验通过即重算 BCrypt 写回，老管理员零感知迁移
        if (PasswordUtil.needsUpgrade(a.getPassword())) {
            a.setPassword(PasswordUtil.encode(password));
        }
        a.setLastAccessTime(LocalDateTime.now());
        adminMapper.updateById(a);
        return a;
    }

    @Override
    public Admin getByName(String name) {
        return adminMapper.selectOne(new LambdaQueryWrapper<Admin>().eq(Admin::getName, name));
    }

    @Override
    public Admin getById(Long id) {
        return id == null ? null : adminMapper.selectById(id);
    }

    @Override
    @Transactional
    public Admin add(AdminAddDTO dto) {
        if (getByName(dto.name()) != null) {
            throw new BusinessException("该管理员已存在");
        }
        Admin a = new Admin();
        a.setName(dto.name());
        // 新口令只写 BCrypt（P1-S2）
        a.setPassword(PasswordUtil.encode(dto.password()));
        a.setAge(dto.age());
        a.setCreateTime(LocalDateTime.now());
        a.setStatus(1);
        a.setPic("https://cube.elemecdn.com/3/7c/3ea6beec64369c2642b92c6726f1epng.png");
        adminMapper.insert(a);
        return a;
    }

    @Override
    @Transactional
    public Admin updateProfile(Long id, String name, Integer age) {
        Admin pre = adminMapper.selectById(id);
        if (pre == null) {
            throw new BusinessException(ResultCode.ADMIN_NOT_FOUND);
        }
        if (name != null && !name.equals(pre.getName()) && getByName(name) != null) {
            throw new BusinessException("管理员名已被占用");
        }
        if (name != null) pre.setName(name);
        if (age != null) pre.setAge(age);
        adminMapper.updateById(pre);
        return pre;
    }

    @Override
    @Transactional
    public void changePassword(Long id, String rawPassword) {
        Admin pre = adminMapper.selectById(id);
        if (pre == null) {
            throw new BusinessException(ResultCode.ADMIN_NOT_FOUND);
        }
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "新密码不能为空");
        }
        // 口令只写 BCrypt；哈希变化使旧令牌 pv 失配，改密即刻全端下线
        pre.setPassword(PasswordUtil.encode(rawPassword));
        adminMapper.updateById(pre);
    }

    @Override
@Transactional
public boolean modifyStatus(String name, int status) {
    if (status != 0 && status != 1) {
        // 白名单：只允许 启用/冻结 两态，防止写入 99 之类脏状态（P1-R5）
        throw new BusinessException(ResultCode.BAD_REQUEST, "状态只能是 0（冻结）或 1（启用）");
    }
    adminMapper.lockAccounts();
    Admin target = getByName(name);
    if (target == null) {
        return false;
    }
    if (status == 0) {
        // 禁止冻结自己：至少留一条能登录退出的通道
        Long currentId = ContextHolder.currentId();
        if (target.getId() != null && target.getId().equals(currentId)) {
            throw new BusinessException("不能冻结当前登录的管理员账号");
        }
        // 冻结前确认还存在另一名启用管理员，避免把系统锁死在无人可管理
        Long others = adminMapper.selectCount(new LambdaQueryWrapper<Admin>()
                .eq(Admin::getStatus, 1).ne(Admin::getId, target.getId()));
        if (others == null || others == 0) {
            throw new BusinessException("至少保留一名启用管理员");
        }
    }
    // 只 SET status：不过实体 updateById，避免把被篡改的其他字段一并写库
    return adminMapper.update(null, new LambdaUpdateWrapper<Admin>()
            .eq(Admin::getId, target.getId()).set(Admin::getStatus, status)) > 0;
}

    @Override
    @Transactional
    public boolean delete(Long id) {
        adminMapper.lockAccounts();
        Admin target = getById(id);
        if (target == null) throw new BusinessException(ResultCode.ADMIN_NOT_FOUND);
        if (id.equals(ContextHolder.currentId())) {
            throw new BusinessException("不能删除当前登录的管理员账号");
        }
        if (Integer.valueOf(1).equals(target.getStatus())) {
            Long others = adminMapper.selectCount(new LambdaQueryWrapper<Admin>()
                    .eq(Admin::getStatus, 1).ne(Admin::getId, id));
            if (others == null || others == 0) throw new BusinessException("至少保留一名启用管理员");
        }
        return adminMapper.deleteById(id) > 0;
    }

    @Override
    public PageResult<Admin> page(int page, int limit, String name, Integer status) {
        if (page < 1 || limit < 1 || limit > 100) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "page需大于0，limit需在1~100之间");
        }
        Page<Admin> req = Page.of(page, limit);
        LambdaQueryWrapper<Admin> wrap = new LambdaQueryWrapper<Admin>()
                .like(name != null && !name.isBlank(), Admin::getName, escapeLike(name))
                .eq(status != null && status != 2, Admin::getStatus, status)
                .orderByDesc(Admin::getCreateTime, Admin::getId);
        Page<Admin> result = adminMapper.selectPage(req, wrap);
        return PageResult.of(result.getTotal(), result.getRecords());
    }

    /** LIKE 关键字转义：用户输入的 %/_ 是通配符，不转义会改变查询语义. */
    private static String escapeLike(String kw) {
        if (kw == null || kw.isBlank()) {
            return kw;
        }
        return kw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    @Override
    public List<Admin> listAll() {
        return adminMapper.selectList(null);
    }
}
