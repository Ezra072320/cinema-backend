package edu.czu.cinema.domain.user.controller;

import edu.czu.cinema.common.api.R;
import edu.czu.cinema.common.api.ResultCode;
import edu.czu.cinema.common.exception.BusinessException;
import edu.czu.cinema.common.util.ContextHolder;
import edu.czu.cinema.common.util.JwtUtil;
import edu.czu.cinema.common.util.PasswordUtil;
import edu.czu.cinema.common.util.SecurityUtils;
import edu.czu.cinema.domain.captcha.CaptchaService;
import edu.czu.cinema.domain.captcha.LoginRiskService;
import edu.czu.cinema.domain.user.dto.PasswordResetDTO;
import edu.czu.cinema.domain.user.dto.UserLoginDTO;
import edu.czu.cinema.domain.user.dto.UserRegisterDTO;
import edu.czu.cinema.domain.user.dto.UserUpdateDTO;
import edu.czu.cinema.domain.user.entity.User;
import edu.czu.cinema.domain.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 用户登录/注册/个人中心接口.
 */
@Tag(name = "01. 用户域")
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final JwtUtil jwtUtil;
    private final CaptchaService captchaService;
    private final LoginRiskService loginRiskService;

    /**
     * 验证码强制开关：true 时 ticket/code 缺失直接拒绝，不再「留空跳过」。
     * 配置键沿用业务配置段，本地调试可在配置里显式关掉。
     */
    @Value("${cinema.captcha.required:true}")
    private boolean captchaRequired;

    @Operation(summary = "登录")
    @PostMapping("/login")
    public R<String> login(@Valid @RequestBody UserLoginDTO dto, HttpServletRequest request) {
        String ip = LoginRiskService.resolveClientIp(request);
        // 1. 验证码（required=true 时缺失即拒；票据一次性，无论对错都销毁）
        verifyCaptcha(dto.captchaTicket(), dto.captchaCode());
        // 2. 「IP+账号」失败锁定：连续 5 次失败锁 10 分钟，锁定期明确提示
        loginRiskService.checkLocked(ip, dto.name());
        User u;
        try {
            u = userService.login(dto.name(), dto.password());
        } catch (BusinessException e) {
            loginRiskService.recordFailure(ip, dto.name());
            throw e;
        }
        loginRiskService.clearFailures(ip, dto.name());
        // 3. 签发令牌；pv=口令版本指纹，AccountStateGuard 每请求复核，改密即刻失效旧令牌
        Map<String, Object> claims = ContextHolder.toClaims(
                new ContextHolder.Principal(u.getId(), u.getName(), "USER"));
        claims.put("pv", PasswordUtil.version(u.getPassword()));
        return R.ok("登录成功", jwtUtil.genToken(claims));
    }

    @Operation(summary = "注册")
    @PostMapping("/register")
    public R<User> register(@Valid @RequestBody UserRegisterDTO dto) {
        verifyCaptcha(dto.captchaTicket(), dto.captchaCode());
        User u = userService.register(dto.name(), dto.password(), dto.age());
        return R.ok(u);
    }

    @Operation(summary = "获取当前登录用户信息")
    @GetMapping("/info")
    public R<User> info() {
        Long id = SecurityUtils.requireUserId();
        // 昵称可修改也可被其他用户重新注册，身份必须始终使用JWT中的不可变ID。
        User u = userService.getById(id);
        return R.ok(u);
    }

    @Operation(summary = "更新自身信息")
    @PostMapping("/update")
    public R<Void> update(@Valid @RequestBody UserUpdateDTO dto) {
        userService.updateCurrent(SecurityUtils.requireUserId(), dto);
        return R.ok();
    }

    @Operation(summary = "重置自身密码")
    @PostMapping("/reset")
    public R<Void> reset(@Valid @RequestBody PasswordResetDTO dto) {
        userService.resetPassword(SecurityUtils.requireUserId(),
                dto.oldPassword(), dto.newPassword(), dto.confirmPassword());
        // 不续签新令牌：旧令牌的 pv 已失配即刻 401，客户端拿新口令重新登录
        return R.ok("密码已更新", null);
    }

    @Operation(summary = "更新自己头像 URL")
    @PostMapping("/modAvatar")
    public R<Void> changeAvatar(@RequestParam String url) {
        Long uid = SecurityUtils.requireUserId();
        userService.changeAvatar(uid, url);
        return R.ok();
    }

    /**
     * 验证码统一入口：required=true 时缺失直接业务异常；
     * 只要带了票据就走一次性消费（失败同样销毁票据，4 位码无法反复试）。
     */
    private void verifyCaptcha(String ticket, String code) {
        boolean missing = ticket == null || ticket.isBlank() || code == null || code.isBlank();
        if (missing) {
            if (captchaRequired) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "验证码不能为空");
            }
            // 仅本地调试（显式关掉开关）允许跳过
            return;
        }
        if (!captchaService.verifyAndConsume(ticket, code)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "验证码错误或已过期");
        }
    }
}
