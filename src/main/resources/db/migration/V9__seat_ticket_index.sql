-- 复制自 db/migrate/2026_10_16__seat_ticket_index.sql，顺序=README 已验证顺序
-- =============================================================
-- 座位图改造补充: 给 seat / ticket 补索引与唯一约束
-- (2026-10-16)
-- =============================================================
-- 背景:
--   这两张表建表时只有主键, 之后座位图加了 version_id 概念也没补索引, 于是:
--     1) seat.listByVersion 是**座位唯一的读取路径**(选座 / 排片建票 / 影厅详情全走它),
--        却没有 version_id 索引 -> 每次都全表扫.
--     2) seat 没有 (version_id, y, x) 唯一约束 -> 坐标重复只靠 Java 里一个 Set 拦,
--        并发保存能写进去重复坐标, 前端网格会出现两个座位叠在同一格.
--     3) ticket 没有 schedule_id 索引 -> listBySchedule / countSold 全表扫.
--     4) ticket 没有 (schedule_id, seat_id) 唯一约束 -> 建票逻辑一旦被重复调用就
--        **静默出双份票**(历史上真发生过: 座位曾按 hall_id 查, 跨版本重复建票).
--
-- 跑法(两种都行):
--   bash tools/dbrun.sh src/main/resources/db/migrate/2026_10_16__seat_ticket_index.sql
--   mysql -uroot -p cinema < src/main/resources/db/migrate/2026_10_16__seat_ticket_index.sql
--
-- 关于幂等: 这里**故意不用存储过程 + DELIMITER** —— dbrun.sh 是按 ; 切语句的,
--   DELIMITER 块会被切碎. 所以直接写裸 ALTER, 重跑会报 Duplicate key name,
--   这是预期的(dbrun.sh 遇错继续), 末尾的自检 SELECT 才是判断依据.
--
-- ⚠️ 唯一键在**已有重复数据时会创建失败**. 这是有意的 —— 与其静默跳过,
--    不如让迁移明确报错. 先看下面两个 SELECT 有没有输出, 有就先清理重复行.
-- =============================================================

SET NAMES utf8mb4;
SET sql_notes = 0;

-- ---------- 0. 先查重复: 有输出就先清理, 否则后面建唯一键会失败 ----------

-- 同版本同坐标的重复座位
SELECT '重复座位(同版本同坐标)' AS what, version_id, y, x, COUNT(*) AS cnt,
       GROUP_CONCAT(id ORDER BY id) AS seat_ids
FROM seat
WHERE version_id IS NOT NULL
GROUP BY version_id, y, x
HAVING COUNT(*) > 1;

-- 同场次同座位的重复票
SELECT '重复票(同场次同座位)' AS what, schedule_id, seat_id, COUNT(*) AS cnt,
       GROUP_CONCAT(id ORDER BY id) AS ticket_ids
FROM ticket
GROUP BY schedule_id, seat_id
HAVING COUNT(*) > 1;

-- ---------- 1. 建索引 (重跑报 Duplicate key name 属预期) ----------

ALTER TABLE seat ADD INDEX idx_version (version_id);
ALTER TABLE seat ADD UNIQUE KEY uk_version_xy (version_id, y, x);
ALTER TABLE ticket ADD INDEX idx_schedule (schedule_id);
ALTER TABLE ticket ADD UNIQUE KEY uk_schedule_seat (schedule_id, seat_id);

-- ---------- 2. 自检: 四条都出现才算成功 ----------
SELECT TABLE_NAME, INDEX_NAME, IF(NON_UNIQUE = 0, 'UNIQUE', 'INDEX') AS kind
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('seat', 'ticket')
  AND INDEX_NAME IN ('idx_version', 'uk_version_xy', 'idx_schedule', 'uk_schedule_seat')
GROUP BY TABLE_NAME, INDEX_NAME, NON_UNIQUE
ORDER BY TABLE_NAME, INDEX_NAME;

SET sql_notes = 1;
