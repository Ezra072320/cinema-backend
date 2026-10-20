-- =============================================================
-- 猫头鹰影院 - 种子数据
-- -------------------------------------------------------------
-- 仅供本地/测试环境使用;生产请通过注册流程创建账号.
--
-- 密码均按 {@link edu.czu.cinema.common.util.PasswordUtil#md5} 计算,
--   raw + SALT ("cinema*renovate*2026") -> MD5 hex.
--
-- 内置账号:
--   admin / admin123   (管理员)
--   test  / test123    (普通用户)
-- =============================================================

USE cinema;

-- 幂等清理,避免重复执行时报冲突
DELETE FROM `user`  WHERE `name` IN ('test', 'admin');
DELETE FROM admin   WHERE `name` = 'admin';

INSERT INTO admin (
    `name`, `password`, `age`, `pic`, `status`, create_time, last_access_time
) VALUES (
    'admin',
    '86a69622585ff0f48f7aab92b51d5d77', -- md5("admin123" + SALT)
    28,
    'https://cube.elemecdn.com/3/7c/3ea6beec64369c2642b92c6726f1epng.png',
    1,
    NOW(),
    NOW()
);

INSERT INTO `user` (
    `name`, `password`, `age`, `pic`, `status`, create_time, last_access_time
) VALUES (
    'test',
    '2e07a1b7ebc9883018972c4546a35645', -- md5("test123" + SALT)
    22,
    'https://cube.elemecdn.com/3/7c/3ea6beec64369c2642b92c6726f1epng.png',
    1,
    NOW(),
    NOW()
);
