package edu.czu.cinema.domain.admin.controller;

import edu.czu.cinema.common.api.PageResult;
import edu.czu.cinema.common.api.R;
import edu.czu.cinema.common.api.ResultCode;
import edu.czu.cinema.common.exception.BusinessException;
import edu.czu.cinema.common.util.ContextHolder;
import edu.czu.cinema.common.util.JwtUtil;
import edu.czu.cinema.common.util.PasswordUtil;
import edu.czu.cinema.common.util.SecurityUtils;
import edu.czu.cinema.domain.admin.dto.AdminAddDTO;
import edu.czu.cinema.domain.admin.dto.AdminLoginDTO;
import edu.czu.cinema.domain.admin.dto.AdminStatusDTO;
import edu.czu.cinema.domain.admin.dto.PasswordResetDTO;
import edu.czu.cinema.domain.admin.entity.Admin;
import edu.czu.cinema.domain.admin.service.AdminService;
import edu.czu.cinema.domain.captcha.CaptchaService;
import edu.czu.cinema.domain.captcha.LoginRiskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 管理员登录/管理面接口.
 */
@Tag(name = "02. 管理员域")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;
    private final JwtUtil jwtUtil;
    private final CaptchaService captchaService;
    private final LoginRiskService loginRiskService;

    /** 验证码强制开关：true 时 ticket/code 缺失直接拒绝（默认开启）,本地调试可关. */
    @Value("${cinema.captcha.required:true}")
    private boolean captchaRequired;

    @Operation(summary = "管理员登录")
    @PostMapping("/login")
    public R<String> login(@Valid @RequestBody AdminLoginDTO dto, HttpServletRequest request) {
        String ip = LoginRiskService.resolveClientIp(request);
        // 1. 验证码（required=true 时缺失即拒；票据一次性，无论对错都销毁）
        verifyCaptcha(dto.captchaTicket(), dto.captchaCode());
        // 2. 「IP+账号」失败锁定
        loginRiskService.checkLocked(ip, dto.name());
        Admin a;
        try {
            a = adminService.login(dto.name(), dto.password());
        } catch (BusinessException e) {
            loginRiskService.recordFailure(ip, dto.name());
            throw e;
        }
        loginRiskService.clearFailures(ip, dto.name());
        // 3. 签发令牌；pv=口令版本指纹，AccountStateGuard 每请求复核，改密即刻失效旧令牌
        Map<String, Object> claims = ContextHolder.toClaims(
                new ContextHolder.Principal(a.getId(), a.getName(), "ADMIN"));
        claims.put("pv", PasswordUtil.version(a.getPassword()));
        return R.ok("登录成功", jwtUtil.genToken(claims));
    }

    @Operation(summary = "获取当前登录管理员信息")
    @GetMapping("/info")
    public R<Admin> info() {
        Long id = SecurityUtils.requireAdminId();
        Admin a = adminService.getById(id);
        return R.ok(a);
    }

    @Operation(summary = "新增管理员")
    @PostMapping("/addAdmin")
    public R<Admin> addAdmin(@Valid @RequestBody AdminAddDTO dto) {
        return R.ok(adminService.add(dto));
    }

    @Operation(summary = "更新当前管理员信息")
    @PostMapping("/update")
    public R<Admin> update(@RequestParam String name, @RequestParam Integer age) {
        Long id = SecurityUtils.requireAdminId();
        return R.ok(adminService.updateProfile(id, name, age));
    }

    @Operation(summary = "管理员重置自身密码")
    @PostMapping("/reset")
    public R<Void> reset(@Valid @RequestBody PasswordResetDTO dto) {
        Long id = SecurityUtils.requireAdminId();
        Admin a = adminService.getById(id);
        if (a == null) {
            return R.fail(ResultCode.ADMIN_NOT_FOUND, "管理员不存在");
        }
        // 服务端 null 防护：@NotBlank 只保护 HTTP 路径，不依赖它
        if (dto.newPassword() == null || dto.confirmPassword() == null
                || !dto.newPassword().equals(dto.confirmPassword())) {
            return R.fail("两次输入的新密码不一致");
        }
        if (!PasswordUtil.matches(dto.oldPassword(), a.getPassword())) {
            return R.fail(ResultCode.ADMIN_PASSWORD_ERROR, "原密码不正确");
        }
        adminService.changePassword(id, dto.newPassword());
        // 不续签：旧令牌 pv 失配即刻 401，重新登录拿新口令
        return R.ok();
    }

    @Operation(summary = "管理员列表（分页）")
    @GetMapping("/list")
    public R<PageResult<Admin>> list(@RequestParam(required = false) String name,
                                     @RequestParam(required = false) Integer status) {
        return R.ok(adminService.page(name, status));
    }

    @Operation(summary = "切换账号状态（PATCH body {name,status}）")
    @PatchMapping("/modStatus")
    public R<Void> modStatus(@Valid @RequestBody AdminStatusDTO dto) {
        // 手工拆参 Map 已替换为带校验的 DTO；冻结自己的账号/冻结最后一个启用管理员由服务层拒绝
        boolean ok = adminService.modifyStatus(dto.name(), dto.status());
        return ok ? R.ok() : R.fail("操作失败");
    }

    private void verifyCaptcha(String ticket, String code) {
        boolean missing = ticket == null || ticket.isBlank() || code == null || code.isBlank();
        if (missing) {
            if (captchaRequired) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "验证码不能为空");
            }
            return;
        }
        if (!captchaService.verifyAndConsume(ticket, code)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "验证码错误或已过期");
        }
    }
}