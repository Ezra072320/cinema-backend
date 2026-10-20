-- =============================================================
-- 影厅座位图改造 Phase 5: 排片锁定座位图版本
-- (2026-10-16)
-- =============================================================
-- 目的:
-- 1) schedule 加 seat_version_id, 排片时把「那一刻的座位图版本」锁死.
--    之后管理员改座位图并发布新版本, 老场次仍指向老版本, 已售出的票永远能对上座位.
-- 2) 顺带修一个回归: 座位表 Phase 2 起带 version_id, 一个厅会有多份座位行.
--    排片建票/影厅详情如果还按 hall_id 查, 会拿到跨版本重复的座位.
--    有了 seat_version_id, 所有查询都能收口到「该场次的版本」.
--
-- 回填策略:
--   老场次(Phase 3 之前排的)当时用的就是该厅最初的座位图, 所以指向该厅**最早版本**
--   (MIN(version)), 而不是 current_version_id —— 后者可能已经被管理员发布过 v2/v3.
--   没有版本可指的场次(不该出现)保持 NULL, 后端读取时兜底回退到 hall.current_version_id.
-- =============================================================

SET NAMES utf8mb4;
SET sql_notes = 0;

-- 1. 加列
ALTER TABLE `schedule`
    ADD COLUMN seat_version_id BIGINT DEFAULT NULL
        COMMENT '排片时锁定的座位图版本, 指向 hall_seat_version.id. 场次生命周期内不变';

-- 2. 索引: 按版本查座位/票是常见路径
ALTER TABLE `schedule`
    ADD INDEX idx_seat_version (seat_version_id);

-- 3. 回填: 每个场次指向它所属影厅的最早版本
UPDATE `schedule` s
JOIN (
    SELECT hall_id, MIN(version) AS min_version
    FROM hall_seat_version
    GROUP BY hall_id
) mv ON mv.hall_id = s.hall_id
JOIN hall_seat_version v ON v.hall_id = s.hall_id AND v.version = mv.min_version
SET s.seat_version_id = v.id
WHERE s.seat_version_id IS NULL;

-- 4. 自检输出: 还有多少场次没锁到版本(应为 0)
SELECT
    (SELECT COUNT(*) FROM `schedule`) AS schedule_count,
    (SELECT COUNT(*) FROM `schedule` WHERE seat_version_id IS NULL) AS schedule_without_version;

SET sql_notes = 1;
