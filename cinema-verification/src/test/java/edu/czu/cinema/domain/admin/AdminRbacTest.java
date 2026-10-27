package edu.czu.cinema.domain.admin;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import edu.czu.cinema.common.exception.BusinessException;
import edu.czu.cinema.common.util.ContextHolder;
import edu.czu.cinema.domain.admin.access.AdminRbacService;
import edu.czu.cinema.domain.admin.entity.Admin;
import edu.czu.cinema.domain.admin.mapper.AdminMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AdminRbacTest {
  JdbcTemplate db;
  AdminMapper admins;
  AdminRbacService rbac;

  @BeforeEach
  void setup() throws Exception {
    db =
        new JdbcTemplate(
            new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
    db.execute("CREATE TABLE admin(id BIGINT PRIMARY KEY,status INT)");
    db.update("INSERT INTO admin VALUES(1,1),(2,1),(3,1)");
    String migration =
        new ClassPathResource("db/migration/V3__admin_rbac.sql")
            .getContentAsString(StandardCharsets.UTF_8)
            .replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "");
    for (String sql : migration.split(";")) if (!sql.isBlank()) db.execute(sql);
    admins = mock(AdminMapper.class);
    rbac = new AdminRbacService(db, admins);
    for (long id : List.of(1L, 2L, 3L)) {
      var a = new Admin();
      a.setId(id);
      a.setStatus(1);
      when(admins.selectById(id)).thenReturn(a);
    }
    ContextHolder.set(new ContextHolder.Principal(1L, "owner", "ADMIN"));
  }

  @AfterEach
  void clear() {
    ContextHolder.clear();
  }

  @Test
  void existingAccountsKeepTheirRightsAndRolesUnionWithoutDuplicates() {
    assertThat(rbac.roles(2)).containsExactly("OWNER");
    var updated = rbac.assign(2, Set.of("VIEWER", "FINANCE"));
    assertThat(updated.permissions())
        .contains("catalog:read", "report:read")
        .doesNotContain("catalog:write", "admin:manage");
    assertThat(db.queryForObject("SELECT COUNT(*) FROM admin_access_audit", Long.class))
        .isEqualTo(1);
    verify(admins).lockAccounts();
  }

  @Test
  void unknownRolesAndSelfDemotionCannotChangePersistedGrants() {
    assertThatThrownBy(() -> rbac.assign(2, Set.of("invented")))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> rbac.assign(1, Set.of("VIEWER")))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("当前登录");
    assertThat(rbac.roles(1)).containsExactly("OWNER");
    assertThat(rbac.roles(2)).containsExactly("OWNER");
    assertThat(db.queryForObject("SELECT COUNT(*) FROM admin_access_audit", Long.class)).isZero();
  }

  @Test
  void lifecycleCannotRemoveLastEnabledOwnerEvenWhenOtherAdminsExist() {
    rbac.assign(2, Set.of("OPERATOR"));
    rbac.assign(3, Set.of("VIEWER"));
    assertThatThrownBy(() -> rbac.requireOtherOwner(admins.selectById(1L)))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("超级管理员");
    assertThat(rbac.permissions(2)).doesNotContain("admin:manage");
    ContextHolder.set(new ContextHolder.Principal(2L, "operator", "ADMIN"));
    assertThatThrownBy(() -> rbac.assign(3, Set.of("OWNER")))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("无权");
  }

  @Test
  void newAccountsStartReadOnlyAndAssignmentLookupIsBounded() {
    db.update("INSERT INTO admin VALUES(4,1)");
    rbac.defaultRole(4);
    assertThat(rbac.roles(4)).containsExactly("VIEWER");
    assertThat(rbac.permissions(4)).contains("venue:read").doesNotContain("venue:write");
    assertThat(rbac.assignments(List.of(1L, 4L))).containsEntry(4L, Set.of("VIEWER"));
    assertThatThrownBy(() -> rbac.assignments(List.of(-1L))).isInstanceOf(BusinessException.class);
  }
}
