-- =============================================================
-- 批量排片批次号回填 (2026-10-18)
-- =============================================================
-- 背景:
--   batch_id 是 2026-10-18 才加的列, 之前的批量排片场次全是 NULL ——
--   时间轴拖拽时识别不出"这批是批量排的", 三选一确认不会弹.
--
-- 回填口径(启发式, 没有历史批次记录只能按特征归并):
--   同一【影片 + 影厅 + 开场时刻 + 票价】且跨 ≥2 天(≥2 场)的场次视为同一批,
--   共享一个批次号. 同一批次里同片同厅同时刻同价本来就该一起改, 合并无副作用;
--   反之若两批特征完全相同, 合并后一起改期也正是"同时段整体改期"的期望语义.
--
-- 批次号取该组特征的 MD5(32 位十六进制, 落在 VARCHAR(36) 里) —— 确定性、可复算,
-- 重复执行结果一致. 只填 batch_id IS NULL 的行, 可重复执行.
--
-- 回滚: UPDATE `schedule` SET batch_id = NULL WHERE batch_id IN (...);
--       (或整列置 NULL, 不影响其它数据)
-- =============================================================

SET NAMES utf8mb4;
SET sql_notes = 0;

-- 自检输出(执行前): 有多少待回填的行、会归并成多少批
SELECT
    (SELECT COUNT(*) FROM `schedule` WHERE batch_id IS NULL) AS null_before,
    (SELECT COUNT(*) FROM (
        SELECT 1 FROM `schedule` WHERE batch_id IS NULL
        GROUP BY movie_id, hall_id, TIME(start_time), price
        HAVING COUNT(*) >= 2
    ) t) AS groups_to_fill;

-- 派生表只 SELECT 分组表达式(only_full_group_by 友好), 哈希在 SET 里用别名算
UPDATE `schedule` s
JOIN (
    SELECT movie_id, hall_id, TIME(start_time) AS slot, price
    FROM `schedule`
    WHERE batch_id IS NULL
    GROUP BY movie_id, hall_id, TIME(start_time), price
    HAVING COUNT(*) >= 2
) g
  ON g.movie_id = s.movie_id
 AND g.hall_id = s.hall_id
 AND g.slot = TIME(s.start_time)
 AND g.price = s.price
SET s.batch_id = MD5(CONCAT_WS('-', g.movie_id, g.hall_id, g.slot, g.price))
WHERE s.batch_id IS NULL;

-- 自检输出(执行后): 剩余 NULL 行数(单场排片与孤立场次, 属正常)
SELECT
    (SELECT COUNT(*) FROM `schedule`) AS schedule_count,
    (SELECT COUNT(*) FROM `schedule` WHERE batch_id IS NOT NULL) AS with_batch,
    (SELECT COUNT(*) FROM `schedule` WHERE batch_id IS NULL) AS single_or_orphan;

SET sql_notes = 1;
