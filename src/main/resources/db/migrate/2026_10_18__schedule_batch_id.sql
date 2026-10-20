-- =============================================================
-- 批量排片批次号 (2026-10-18)
-- =============================================================
-- 目的:
--   schedule 加 batch_id, 标识「同一次批量排片」创建的场次.
--   时间轴拖拽改期时据此识别批量场次, 弹出「整批同时段改期 / 只改这一天」确认.
--
--   单场排片与历史数据保持 NULL —— 视为单场, 拖拽不弹批量确认.
--
-- 可重复执行.
-- =============================================================

SET NAMES utf8mb4;
SET sql_notes = 0;

DROP PROCEDURE IF EXISTS _cinema_schedule_batch_id;
DELIMITER //
CREATE PROCEDURE _cinema_schedule_batch_id()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema=DATABASE() AND table_name='schedule' AND column_name='batch_id') THEN
        ALTER TABLE `schedule`
            ADD COLUMN batch_id VARCHAR(36) DEFAULT NULL
                COMMENT '批量排片批次号(UUID)。单场排片与历史数据为 NULL';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema=DATABASE() AND table_name='schedule' AND index_name='idx_schedule_batch') THEN
        ALTER TABLE `schedule` ADD INDEX idx_schedule_batch (batch_id);
    END IF;
END //
DELIMITER ;
CALL _cinema_schedule_batch_id();
DROP PROCEDURE _cinema_schedule_batch_id;

-- 自检输出: batch_id 列应存在, 历史数据为 NULL
SELECT
    (SELECT COUNT(*) FROM `schedule`) AS schedule_count,
    (SELECT COUNT(*) FROM `schedule` WHERE batch_id IS NOT NULL) AS with_batch;

SET sql_notes = 1;
