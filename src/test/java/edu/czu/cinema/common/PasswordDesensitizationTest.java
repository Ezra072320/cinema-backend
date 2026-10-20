package edu.czu.cinema.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.czu.cinema.domain.admin.entity.Admin;
import edu.czu.cinema.domain.user.entity.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * 口令脱敏回归(P1-S1 / 0.3): User/Admin 的 password 设了 WRITE_ONLY —— 序列化出响应时必须不含口令哈希.
 * 防止未来有人误把注解去掉、把口令哈希泄露给管理列表接口.
 */
class PasswordDesensitizationTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void userJsonDoesNotExposePasswordOrOpenid() throws Exception {
        User u = new User();
        u.setId(1L);
        u.setName("测试用户");
        u.setPassword("$2a$10$abcdefg");
        u.setOpenid("wx-openid-secret");
        String json = om.writeValueAsString(u);
        assertThat(json).contains("name").doesNotContain("password").doesNotContain("openid");
    }

    @Test
    void adminJsonDoesNotExposePassword() throws Exception {
        Admin a = new Admin();
        a.setId(1L);
        a.setName("admin");
        a.setPassword("$2a$10$abcdefg");
        String json = om.writeValueAsString(a);
        assertThat(json).contains("name").doesNotContain("password");
    }
}