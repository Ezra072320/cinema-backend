package edu.czu.cinema.domain.admin.access;

import edu.czu.cinema.common.exception.BusinessException;
import edu.czu.cinema.common.util.SecurityUtils;
import edu.czu.cinema.domain.admin.entity.Admin;
import edu.czu.cinema.domain.admin.mapper.AdminMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminRbacService {
  private final JdbcTemplate db;
  private final AdminMapper admins;

  public record Access(Set<String> roles, Set<String> permissions) {}

  public record Role(String code, String name, Set<String> permissions) {}

  public AdminRbacService(JdbcTemplate db, AdminMapper admins) {
    this.db = db;
    this.admins = admins;
  }

  public Set<String> roles(long id) {
    return new TreeSet<>(
        db.queryForList(
            "SELECT role_code FROM admin_role_assignment WHERE admin_id=?", String.class, id));
  }

  public Set<String> permissions(long id) {
    return new TreeSet<>(
        db.queryForList(
            """
            SELECT DISTINCT p.permission_code FROM admin_role_assignment a
            JOIN admin_role_permission p ON p.role_code=a.role_code WHERE a.admin_id=?
            """,
            String.class,
            id));
  }

  public Access access(long id) {
    return new Access(roles(id), permissions(id));
  }

  public List<Role> definitions() {
    return db.query(
        "SELECT code,name FROM admin_role_definition ORDER BY code",
        (rs, row) ->
            new Role(
                rs.getString(1),
                rs.getString(2),
                new TreeSet<>(
                    db.queryForList(
                        "SELECT permission_code FROM admin_role_permission WHERE role_code=?",
                        String.class,
                        rs.getString(1)))));
  }

  public Map<Long, Set<String>> assignments(List<Long> ids) {
    if (ids.isEmpty() || ids.size() > 100 || ids.stream().anyMatch(id -> id == null || id <= 0))
      throw new BusinessException("账号列表不合法");
    Map<Long, Set<String>> result = new LinkedHashMap<>();
    ids.forEach(id -> result.put(id, new TreeSet<>()));
    db.query(
        "SELECT admin_id,role_code FROM admin_role_assignment WHERE admin_id IN ("
            + String.join(",", Collections.nCopies(ids.size(), "?"))
            + ")",
        (org.springframework.jdbc.core.RowCallbackHandler)
            rs -> result.get(rs.getLong(1)).add(rs.getString(2)),
        ids.toArray());
    return result;
  }

  public void defaultRole(long id) {
    db.update("INSERT INTO admin_role_assignment(admin_id,role_code) VALUES(?,'VIEWER')", id);
  }

  /** Caller holds AdminMapper.lockAccounts() for lifecycle and role changes. */
  public void requireOtherOwner(Admin target) {
    if (!Integer.valueOf(1).equals(target.getStatus()) || !roles(target.getId()).contains("OWNER"))
      return;
    Long others =
        db.queryForObject(
            """
            SELECT COUNT(*) FROM admin_role_assignment r JOIN admin a ON a.id=r.admin_id
            WHERE r.role_code='OWNER' AND a.status=1 AND a.id<>?
            """,
            Long.class,
            target.getId());
    if (others == null || others == 0) throw new BusinessException("至少保留一名启用的超级管理员");
  }

  @Transactional
  public Access assign(long targetId, Set<String> requested) {
    admins.lockAccounts();
    long actor = SecurityUtils.requireAdminId();
    if (!permissions(actor).contains("admin:manage")) throw new BusinessException(2002, "无权调整角色");
    Admin target = admins.selectById(targetId);
    if (target == null) throw new BusinessException("管理员不存在");
    if (requested == null || requested.stream().anyMatch(Objects::isNull))
      throw new BusinessException("角色选择不合法");
    Set<String> next = new TreeSet<>(requested);
    Set<String> available =
        new HashSet<>(db.queryForList("SELECT code FROM admin_role_definition", String.class));
    if (next.isEmpty() || next.size() > 4 || !available.containsAll(next))
      throw new BusinessException("角色选择不合法");
    Set<String> before = roles(targetId);
    if (before.equals(next)) return access(targetId);
    if (actor == targetId) throw new BusinessException("不能调整当前登录账号的角色，请由另一名超级管理员操作");
    if (before.contains("OWNER") && !next.contains("OWNER")) requireOtherOwner(target);
    db.update("DELETE FROM admin_role_assignment WHERE admin_id=?", targetId);
    next.forEach(
        role ->
            db.update(
                "INSERT INTO admin_role_assignment(admin_id,role_code) VALUES(?,?)",
                targetId,
                role));
    db.update(
        "INSERT INTO admin_access_audit(actor_id,target_id,before_roles,after_roles)"
            + " VALUES(?,?,?,?)",
        actor,
        targetId,
        String.join(",", before),
        String.join(",", next));
    return access(targetId);
  }
}
