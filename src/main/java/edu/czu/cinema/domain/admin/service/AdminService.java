package edu.czu.cinema.domain.admin.service;

import edu.czu.cinema.common.api.PageResult;
import edu.czu.cinema.domain.admin.dto.AdminAddDTO;
import edu.czu.cinema.domain.admin.entity.Admin;

import java.util.List;

public interface AdminService {

    Admin login(String name, String password);

    Admin getByName(String name);

    Admin getById(Long id);

    Admin add(AdminAddDTO dto);

    Admin updateProfile(Long id, String name, Integer age);

    /** 仅修改密码（保留名字/年龄不动） */
    void changePassword(Long id, String rawPassword);

    boolean modifyStatus(String name, int status);

    PageResult<Admin> page(String name, Integer status);

    List<Admin> listAll();
}
