package edu.czu.cinema.domain.user.service;

import edu.czu.cinema.common.api.PageResult;
import edu.czu.cinema.domain.user.dto.UserUpdateDTO;
import edu.czu.cinema.domain.user.entity.User;

public interface UserService {

    User getByName(String name);

    User getById(Long id);

    User login(String name, String password);

    /**
     * 注册.
     *
     * @param age 可为 null(前端未填/调试用),内部默认 0
     */
    User register(String name, String password, Integer age);

    /** 自身资料更新：只接受 name/age（pic/status 不可自助写，防自我解冻）. */
    void updateCurrent(Long id, UserUpdateDTO dto);

    /**
     * 管理端编辑用户：白名单字段 name/age/pic/status，口令/创建时间永不可写；
     * status 仅允许 0/1，不传表示不改（杜绝「字段默认 1」导致的静默解冻）。
     */
    boolean updateManaged(UserUpdateDTO dto);

    void resetPassword(Long id, String oldPwd, String newPwd, String confirm);

    void changeAvatar(Long id, String url);

    boolean modifyStatus(String name, int status);

    boolean modifyStatus(Long id, int status);

    boolean removeById(Long id);

    PageResult<User> page(int page, int size, String name, Integer status);
}
