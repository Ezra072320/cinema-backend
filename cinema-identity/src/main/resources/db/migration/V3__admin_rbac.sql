CREATE TABLE admin_role_definition (
  code VARCHAR(32) NOT NULL PRIMARY KEY,
  name VARCHAR(64) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE admin_permission (
  code VARCHAR(64) NOT NULL PRIMARY KEY,
  name VARCHAR(64) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE admin_role_permission (
  role_code VARCHAR(32) NOT NULL,
  permission_code VARCHAR(64) NOT NULL,
  PRIMARY KEY(role_code, permission_code),
  FOREIGN KEY(role_code) REFERENCES admin_role_definition(code),
  FOREIGN KEY(permission_code) REFERENCES admin_permission(code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE admin_role_assignment (
  admin_id BIGINT NOT NULL,
  role_code VARCHAR(32) NOT NULL,
  PRIMARY KEY(admin_id, role_code),
  FOREIGN KEY(admin_id) REFERENCES admin(id) ON DELETE CASCADE,
  FOREIGN KEY(role_code) REFERENCES admin_role_definition(code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE admin_access_audit (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  actor_id BIGINT NOT NULL,
  target_id BIGINT NOT NULL,
  before_roles VARCHAR(256) NOT NULL,
  after_roles VARCHAR(256) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO admin_role_definition VALUES
 ('OWNER','超级管理员'),('OPERATOR','影院运营'),('FINANCE','财务人员'),('VIEWER','只读人员');
INSERT INTO admin_permission VALUES
 ('dashboard:read','查看影院概览'),('catalog:read','查看影片管理'),('catalog:write','同步和编辑影片'),
 ('venue:read','查看影厅配置'),('venue:write','管理影厅和座位图'),
 ('schedule:read','查看排片管理'),('schedule:write','新增、改期和取消排片'),
 ('order:read','查看订单管理'),('report:read','查看营收报表'),('report:export','导出营收报表'),
 ('benefit:read','查看优惠券管理'),('benefit:write','发布优惠券'),
 ('user:manage','管理用户账号'),('admin:manage','管理管理员与角色'),('governance:manage','管理流量规则');
INSERT INTO admin_role_permission SELECT 'OWNER',code FROM admin_permission;
INSERT INTO admin_role_permission SELECT 'OPERATOR',code FROM admin_permission
 WHERE code IN ('dashboard:read','catalog:read','catalog:write','venue:read','venue:write',
 'schedule:read','schedule:write','order:read','benefit:read','benefit:write','user:manage');
INSERT INTO admin_role_permission SELECT 'FINANCE',code FROM admin_permission
 WHERE code IN ('dashboard:read','order:read','report:read','report:export');
INSERT INTO admin_role_permission SELECT 'VIEWER',code FROM admin_permission
 WHERE code IN ('dashboard:read','catalog:read','venue:read','schedule:read','benefit:read');
-- Preserve existing administrators' capabilities. New accounts start as VIEWER.
INSERT INTO admin_role_assignment SELECT id,'OWNER' FROM admin;
