-- 复制自 db/migrate/2026_10_16__seat_version_draft_lock.sql，顺序=README 已验证顺序
-- =============================================================
-- 影厅座位图改造 Phase 3: 草稿乐观锁
-- (2026-10-16)
-- =============================================================
-- 目的: 给 hall_seat_version 加 draft_lock_version 字段, 草稿更新时 +1,
--       防止两个管理员同时编辑同一草稿(后者提示"已被改, 请刷新").
--
-- 设计:
--  - draft_lock_version=0 表示没有正在编辑的草稿(只针对「草稿」语义).
--    已发布版本该字段没意义, 保持 0 即可.
--  - 草稿保存路径: 读 draft_lock_version, 校验 client 传来的 expectedVersion,
--    匹配则 +1 写回; 不匹配抛 409.
-- =============================================================

SET NAMES utf8mb4;
SET sql_notes = 0;

ALTER TABLE hall_seat_version
    ADD COLUMN draft_lock_version INT NOT NULL DEFAULT 0
        COMMENT '草稿乐观锁版本. 已发布版本保持 0, 草稿每次保存 +1';

SET sql_notes = 1;
