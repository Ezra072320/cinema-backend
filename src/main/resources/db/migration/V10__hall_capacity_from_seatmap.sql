-- 复制自 db/migrate/2026_10_16__hall_capacity_from_seatmap.sql，顺序=README 已验证顺序
-- =============================================================
-- 影厅座位数改为「由座位图算出来」, 去掉手工填写与冗余列
-- (2026-10-16)
-- =============================================================
-- 背景:
--   原先 hall 上有两个表达"座位数"的字段, 语义重叠且都不对:
--     - capacity   : 建厅时管理员手填的额定座位数(前端一个输入框)
--     - total_seats: Phase 3 加的, 发布座位图时由后端算的 status=1 座位数
--   结果是"填的"和"实际画的"经常对不上, 而且门户影厅列表显示的是**手填的那个**
--   (views/portal/Hall.vue: `{{ h.capacity }} 座`) —— 画了 100 个座位但当初填了 120,
--   观众看到的就是 120.
--
-- 改成:
--   **只保留 capacity 一个字段, 且它由座位图算出来** —— 发布座位图时写入
--   "当前版本里 status=1 的座位数". 建厅时不再填座位数.
--   capacity 是门户在用的字段, 保留它改动最小; total_seats 删除, 否则两个字段
--   一个含义迟早又会对不上.
--
-- 幂等: 回填两条 UPDATE 可重跑; DROP COLUMN 重跑会报
--   "Can't DROP 'total_seats'" —— 预期, dbrun.sh 遇错继续.
-- =============================================================

SET NAMES utf8mb4;
SET sql_notes = 0;

-- 1. 先把 capacity 全部归零, 再由座位图重新算 —— 避免残留手填值
UPDATE hall SET capacity = 0;

-- 2. 按「当前生效版本里 status=1 的座位数」回填.
--    没有 current_version_id 或该版本没有可售座位的厅, 保持 0 (表示还没画座位图).
UPDATE hall h
JOIN (
    SELECT s.version_id, COUNT(*) AS cnt
    FROM seat s
    WHERE s.status = 1
    GROUP BY s.version_id
) c ON c.version_id = h.current_version_id
SET h.capacity = c.cnt;

-- 3. 删掉冗余列
ALTER TABLE hall DROP COLUMN total_seats;

-- 4. 自检: 每个厅的 capacity 应等于它当前版本里 status=1 的座位数
SELECT h.id, h.name, h.capacity,
       IFNULL((SELECT COUNT(*) FROM seat s
               WHERE s.version_id = h.current_version_id AND s.status = 1), 0) AS computed,
       IF(h.capacity = IFNULL((SELECT COUNT(*) FROM seat s
                               WHERE s.version_id = h.current_version_id AND s.status = 1), 0),
          'OK', 'MISMATCH') AS chk
FROM hall h;

SET sql_notes = 1;
