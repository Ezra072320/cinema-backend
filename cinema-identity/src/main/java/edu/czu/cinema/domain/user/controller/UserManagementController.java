package edu.czu.cinema.domain.user.controller;

import edu.czu.cinema.common.api.PageResult;
import edu.czu.cinema.common.api.R;
import edu.czu.cinema.common.util.SecurityUtils;
import edu.czu.cinema.domain.user.dto.StatusDTO;
import edu.czu.cinema.domain.user.dto.UserRegisterDTO;
import edu.czu.cinema.domain.user.dto.UserUpdateDTO;
import edu.czu.cinema.domain.user.entity.User;
import edu.czu.cinema.domain.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 管理员对普通用户进行 CRUD 的入口.
 * 避免 {@link UserController} 与 AdminController 都堆积巨型接口.
 */
@Tag(name = "14. 用户管理（管理员视角）")
@RestController
@RequestMapping("/api/admin/user")
@RequiredArgsConstructor
public class UserManagementController {

    private final UserService userService;

    @Operation(summary = "用户分页")
    @GetMapping("/list")
    public R<PageResult<User>> list(@RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "10") int limit,
                                    @RequestParam(required = false) String name,
                                    @RequestParam(required = false, defaultValue = "2") Integer status) {
        SecurityUtils.requireAdminId();
        // 分页边界在服务层校验（page≥1、limit 1..100），这里不再透传非法值
        return R.ok(userService.page(page, limit, name, status));
    }

    @Operation(summary = "新建用户（复用注册 DTO 校验，口令走 BCrypt）")
    @PostMapping("/add")
    public R<User> add(@Valid @RequestBody UserRegisterDTO dto) {
        SecurityUtils.requireAdminId();
        // captcha 字段对管理端无意义，忽略；其余字段与注册同一套 Bean Validation
        return R.ok(userService.register(dto.name(), dto.password(), dto.age()));
    }

    @Operation(summary = "编辑保存用户（白名单字段 name/age/pic/status）")
    @PostMapping("/save")
    public R<Boolean> save(@Valid @RequestBody UserUpdateDTO dto) {
        SecurityUtils.requireAdminId();
        // 专用 DTO + 服务层白名单更新：口令/创建时间不可写，status 不传即不改
        return R.ok(userService.updateManaged(dto));
    }

    @Operation(summary = "修改状态（PUT body {id,status}）")
    @PutMapping("/status")
    public R<Boolean> changeStatus(@Valid @RequestBody StatusDTO dto) {
        SecurityUtils.requireAdminId();
        return R.ok(userService.modifyStatus(dto.id(), dto.status()));
    }

    @Operation(summary = "删除用户（DELETE 真删）")
    @DeleteMapping("/delete/{id}")
    public R<Boolean> delete(@PathVariable Long id) {
        SecurityUtils.requireAdminId();
        if (userService.getById(id) == null) return R.fail("用户不存在");
        return R.ok(userService.removeById(id));
    }
}
