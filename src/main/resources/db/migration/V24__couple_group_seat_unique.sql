-- =============================================================
-- 情侣对跨列唯一约束 (2026-10-20)
-- =============================================================
-- 目的: seat_couple_group 原有 uk_version_seat1/uk_version_seat2 是两个独立唯一键,
-- 不阻止同一座位出现在不同对的不同列（X 既是 pairA.seat_id_1 又是 pairB.seat_id_2）。
-- 加生成列 seat_a=LEAST(seat_id_1,seat_id_2)、seat_b=GREATEST(...),
-- 对 (version_id,seat_a)、(version_id,seat_b) 建唯一键，一座只能属于一对。
--
-- 可重复执行；历史脏数据（一座两对）会让唯一键跳过，自检输出暴露。
-- =============================================================

SET NAMES utf8mb4;
SET sql_notes = 0;

DROP PROCEDURE IF EXISTS _cinema_couple_seat_unique;
DELIMITER //
CREATE PROCEDURE _cinema_couple_seat_unique()
BEGIN
    DECLARE dup INT;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema=DATABASE() AND table_name='seat_couple_group' AND column_name='seat_a') THEN
        ALTER TABLE seat_couple_group
            ADD COLUMN seat_a BIGINT AS (LEAST(seat_id_1, seat_id_2)) STORED,
            ADD COLUMN seat_b BIGINT AS (GREATEST(seat_id_1, seat_id_2)) STORED;
    END IF;

    SELECT COUNT(*) INTO dup FROM (
        SELECT version_id, seat_a FROM seat_couple_group GROUP BY version_id, seat_a HAVING COUNT(*) > 1
        UNION ALL
        SELECT version_id, seat_b FROM seat_couple_group GROUP BY version_id, seat_b HAVING COUNT(*) > 1
    ) d;

    IF dup = 0 THEN
        IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                       WHERE table_schema=DATABASE() AND table_name='seat_couple_group' AND index_name='uk_couple_seat_a') THEN
            ALTER TABLE seat_couple_group ADD UNIQUE KEY uk_couple_seat_a (version_id, seat_a);
        END IF;
        IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                       WHERE table_schema=DATABASE() AND table_name='seat_couple_group' AND index_name='uk_couple_seat_b') THEN
            ALTER TABLE seat_couple_group ADD UNIQUE KEY uk_couple_seat_b (version_id, seat_b);
        END IF;
    END IF;
END //
DELIMITER ;
CALL _cinema_couple_seat_unique();
DROP PROCEDURE _cinema_couple_seat_unique;

-- 自检输出: dup_couple_seats 应为 0；new_unique_keys 应为 2
SELECT
    (SELECT COUNT(*) FROM (
        SELECT version_id, seat_a FROM seat_couple_group GROUP BY version_id, seat_a HAVING COUNT(*) > 1
        UNION ALL
        SELECT version_id, seat_b FROM seat_couple_group GROUP BY version_id, seat_b HAVING COUNT(*) > 1
    ) d) AS dup_couple_seats,
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema=DATABASE() AND table_name='seat_couple_group'
       AND index_name IN ('uk_couple_seat_a','uk_couple_seat_b')) AS new_unique_keys;

SET sql_notes = 1;
