package edu.czu.cinema.domain.admin;

import edu.czu.cinema.common.exception.GlobalExceptionHandler;
import edu.czu.cinema.common.util.JwtUtil;
import edu.czu.cinema.domain.admin.controller.AdminController;
import edu.czu.cinema.domain.admin.service.AdminService;
import edu.czu.cinema.domain.captcha.CaptchaService;
import edu.czu.cinema.domain.captcha.LoginRiskService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminDeleteContractTest {
    @Test void deletionHasAnAdminEndpointInsteadOfUsingTheUserController() throws Exception {
        var service = mock(AdminService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new AdminController(service, mock(JwtUtil.class),
                mock(CaptchaService.class), mock(LoginRiskService.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(delete("/api/admin/delete/9"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1));
        verify(service).delete(9L);
    }
}
