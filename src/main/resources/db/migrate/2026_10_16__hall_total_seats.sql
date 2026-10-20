-- =============================================================
-- 影厅座位图改造 Phase 3 补: hall.total_seats 列
-- (2026_10_16)
-- =============================================================
-- Phase 3 的 SeatVersionService.applyDraft 需要同步 hall.total_seats
-- (status=1 的座位数), 排片时直接读这个字段, 不必每次重算.
--
-- 老数据 (Phase 2 回填的) total_seats 为 NULL, 不影响, 管理员下次发布草稿时
-- 会自动填上.
-- =============================================================

SET NAMES utf8mb4;
SET sql_notes = 0;

ALTER TABLE hall
    ADD COLUMN total_seats INT DEFAULT NULL
        COMMENT '物理座位数, status=1 的座位. 由 SeatVersionService.applyDraft 同步.';

SET sql_notes = 1;
