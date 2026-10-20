-- =============================================================
-- 核心表索引与唯一约束补齐 (2026-10-20)
-- =============================================================
-- 目的:
-- 1) ticket(order_id)        —— 关单/退款/订单详情按 order_id 查票，此前全表扫
-- 2) order(user_id...)       —— 用户订单列表；order(schedule_id) —— 取消场次捞单
-- 3) order(create_time)      —— 营收按日期范围聚合用不上 (status,create_time) 前导列
-- 4) order.number UNIQUE     —— 订单号生成器加兜底，碰撞显式失败而非静默重复
-- 5) schedule(hall_id/start_time) 等 —— 冲突检测、按厅/按片查场次
-- 6) score(user_id)          —— 「我的评价」；score(order_id) UNIQUE 补历史库缺口
--
-- 可重复执行；唯一键创建前自检重复数据，存在重复时该键跳过并由自检输出暴露。
-- =============================================================

SET NAMES utf8mb4;
SET sql_notes = 0;

DROP PROCEDURE IF EXISTS _cinema_core_indexes;
DELIMITER //
CREATE PROCEDURE _cinema_core_indexes()
BEGIN
    -- ---- ticket ----
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema=DATABASE() AND table_name='ticket' AND index_name='idx_ticket_order_status') THEN
        ALTER TABLE ticket ADD INDEX idx_ticket_order_status (order_id, status);
    END IF;

    -- ---- order ----
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema=DATABASE() AND table_name='order' AND index_name='idx_order_user_status_create') THEN
        ALTER TABLE `order` ADD INDEX idx_order_user_status_create (user_id, status, create_time);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema=DATABASE() AND table_name='order' AND index_name='idx_order_schedule') THEN
        ALTER TABLE `order` ADD INDEX idx_order_schedule (schedule_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema=DATABASE() AND table_name='order' AND index_name='idx_order_create_time') THEN
        ALTER TABLE `order` ADD INDEX idx_order_create_time (create_time);
    END IF;
    -- 订单号唯一键：历史数据有重复则跳过（自检输出 duplicate_numbers > 0 时人工处理）
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema=DATABASE() AND table_name='order' AND index_name='uk_order_number')
       AND (SELECT COUNT(*) FROM (
              SELECT number FROM `order` GROUP BY number HAVING COUNT(*) > 1) d) = 0 THEN
        ALTER TABLE `order` ADD UNIQUE KEY uk_order_number (number);
    END IF;

    -- ---- schedule ----
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema=DATABASE() AND table_name='schedule' AND index_name='idx_schedule_hall_start') THEN
        ALTER TABLE schedule ADD INDEX idx_schedule_hall_start (hall_id, start_time);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema=DATABASE() AND table_name='schedule' AND index_name='idx_schedule_movie_start') THEN
        ALTER TABLE schedule ADD INDEX idx_schedule_movie_start (movie_id, start_time);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema=DATABASE() AND table_name='schedule' AND index_name='idx_schedule_movie_status_end') THEN
        ALTER TABLE schedule ADD INDEX idx_schedule_movie_status_end (movie_id, status, end_time);
    END IF;

    -- ---- score ----
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema=DATABASE() AND table_name='score' AND index_name='idx_score_user_time') THEN
        ALTER TABLE score ADD INDEX idx_score_user_time (user_id, update_time);
    END IF;
    -- 一单一评唯一键：补齐历史库（建表脚本带 UNIQUE 但旧库可能缺）
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema=DATABASE() AND table_name='score' AND index_name='uk_score_order')
       AND (SELECT COUNT(*) FROM (
              SELECT order_id FROM score GROUP BY order_id HAVING COUNT(*) > 1) d) = 0 THEN
        ALTER TABLE score ADD UNIQUE KEY uk_score_order (order_id);
    END IF;
END //
DELIMITER ;
CALL _cinema_core_indexes();
DROP PROCEDURE _cinema_core_indexes;

-- 自检输出: 索引应存在; duplicate_numbers/duplicate_scores 应为 0（非 0 表示唯一键被跳过）
SELECT
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema=DATABASE()
       AND index_name IN ('idx_ticket_order_status','idx_order_user_status_create','idx_order_schedule',
                          'idx_order_create_time','idx_schedule_hall_start','idx_schedule_movie_start',
                          'idx_schedule_movie_status_end','idx_score_user_time')) AS new_indexes,
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema=DATABASE() AND index_name IN ('uk_order_number','uk_score_order')) AS new_uniques,
    (SELECT COUNT(*) FROM (SELECT number FROM `order` GROUP BY number HAVING COUNT(*)>1) d) AS duplicate_numbers,
    (SELECT COUNT(*) FROM (SELECT order_id FROM score GROUP BY order_id HAVING COUNT(*)>1) d) AS duplicate_scores;

SET sql_notes = 1;
