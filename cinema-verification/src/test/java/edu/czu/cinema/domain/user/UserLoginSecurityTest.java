package edu.czu.cinema.domain.user;

import edu.czu.cinema.common.api.PageResult;
import edu.czu.cinema.common.api.ResultCode;
import edu.czu.cinema.common.exception.BusinessException;
import edu.czu.cinema.common.exception.GlobalExceptionHandler;
import edu.czu.cinema.common.util.ContextHolder;
import edu.czu.cinema.common.util.JwtUtil;
import edu.czu.cinema.domain.admin.controller.AdminController;
import edu.czu.cinema.domain.admin.entity.Admin;
import edu.czu.cinema.domain.admin.service.AdminService;
import edu.czu.cinema.domain.captcha.CaptchaService;
import edu.czu.cinema.domain.captcha.LoginRiskService;
import edu.czu.cinema.domain.user.controller.UserController;
import edu.czu.cinema.domain.user.entity.User;
import edu.czu.cinema.domain.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 登录验证码强制开关（P1-S3）+ 响应脱敏（password/openid 不出 JSON）. */
class UserLoginSecurityTest {

    private final UserService userService = mock(UserService.class);
    private final AdminService adminService = mock(AdminService.class);
    private final CaptchaService captchaService = mock(CaptchaService.class);
    private final LoginRiskService loginRiskService = mock(LoginRiskService.class);
    private final JwtUtil jwtUtil = mock(JwtUtil.class);

    private MockMvc userMvc;
    private MockMvc adminMvc;

    @BeforeEach
    void setup() {
        when(jwtUtil.genToken(any())).thenReturn("TOKEN");
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        userMvc = MockMvcBuilders.standaloneSetup(new UserController(userService, jwtUtil, captchaService, loginRiskService))
                .setControllerAdvice(new GlobalExceptionHandler()).setValidator(validator).build();
        adminMvc = MockMvcBuilders.standaloneSetup(new AdminController(adminService, jwtUtil, captchaService, loginRiskService))
                .setControllerAdvice(new GlobalExceptionHandler()).setValidator(validator).build();
    }

    @AfterEach
    void clean() {
        ContextHolder.clear();
    }

    private String loginBody(String ticket, String code) {
        // 构造与 UserLoginDTO 同构的 JSON：缺 / 带 captcha 两种形态
        String t = ticket == null ? "" : "\"captchaTicket\":\"" + ticket + "\",";
        String c = code == null ? "" : "\"captchaCode\":\"" + code + "\",";
        return "{\"name\":\"alice\"," + c + t + "\"password\":\"pass123\"}";
    }

    @Test
    void loginMissingCaptchaIsRejectedWhenRequired() throws Exception {
        // 直接构造控制器并反射置开关为 true（standalone 不走 Spring 注入 @Value）
        UserController uc = new UserController(userService, jwtUtil, captchaService, loginRiskService);
        ReflectionTestUtils.setField(uc, "captchaRequired", true);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(uc).setControllerAdvice(new GlobalExceptionHandler()).build();

        mvc.perform(post("/api/user/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.BAD_REQUEST.getCode()));
        verify(userService, never()).login(anyString(), anyString());
    }

    @Test
    void loginProceedsWhenCaptchaDisabled() throws Exception {
        UserController uc = new UserController(userService, jwtUtil, captchaService, loginRiskService);
        ReflectionTestUtils.setField(uc, "captchaRequired", false);
        when(userService.login("alice", "pass123")).thenReturn(user(7L, "alice"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(uc).setControllerAdvice(new GlobalExceptionHandler()).build();

        mvc.perform(post("/api/user/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
        verify(userService).login("alice", "pass123");
    }

    @Test
    void userInfoJsonHasNoPasswordOrOpenid() throws Exception {
        ContextHolder.set(new ContextHolder.Principal(7L, "alice", "USER"));
        User u = new User();
        u.setId(7L);
        u.setName("alice");
        u.setPassword("$2a$10$secret-secret-secret-value-here");
        u.setOpenid("wx-openid-0123456789");
        u.setPic("");
        u.setStatus(1);
        when(userService.getById(7L)).thenReturn(u);

        userMvc.perform(get("/api/user/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.openid").doesNotExist());
    }

    @Test
    void adminListJsonHasNoPassword() throws Exception {
        Admin a = new Admin();
        a.setId(1L);
        a.setName("root");
        a.setPassword("$2a$10$admin-secret-value-placeholder");
        a.setStatus(1);
        when(adminService.page(anyInt(), anyInt(), any(), any())).thenReturn(PageResult.of(1L, List.of(a)));

        adminMvc.perform(get("/api/admin/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[0].password").doesNotExist());
    }

    private static User user(long id, String name) {
        User u = new User();
        u.setId(id);
        u.setName(name);
        u.setPassword("$2a$10$placeholder-hash-value-here-0000");
        u.setStatus(1);
        return u;
    }
}
