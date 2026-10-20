-- =============================================================
-- 影厅座位图改造 Phase 4: 情侣对
-- (2026-10-16)
-- =============================================================
-- 设计(已与需求方确认):
--   情侣座 = **两个物理座位 + 一条后端绑定记录**, 不是"一个占两格的逻辑座位".
--   - seat 表里仍是两行, 各自有独立 id, 各自一张票, 检票时两张都要验.
--   - 本表只记录"这两个座位是一对".
--   - 前端把两格渲染成一个视觉单元, 但数据层从未合并.
--
-- 为什么不做成 span_x=2 的单行座位: 那样 seat.id 只能表示一个位置, 卖出一对时
-- 无法为两个座位分别出票, 检票/退票/改签都得特殊处理. 保持两个物理座位,
-- 这些流程跟普通座位完全一致, 只有"必须一起买"这一条额外约束.
--
-- 约束:
--   - 一对必须同排(y 相同)且物理相邻(|x1 - x2| = 1)
--   - 一个座位最多属于一对(两个 UNIQUE KEY 保证)
--   - 情侣对属于某个**座位图版本**, 版本冻结后不可改
-- =============================================================

SET NAMES utf8mb4;
SET sql_notes = 0;

CREATE TABLE IF NOT EXISTS seat_couple_group (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    hall_id    BIGINT NOT NULL
               COMMENT '所属影厅, 冗余便于按厅查',
    version_id BIGINT NOT NULL
               COMMENT '所属座位图版本, 指向 hall_seat_version.id',
    seat_id_1  BIGINT NOT NULL
               COMMENT '一对里的第一个座位(hall_seat_version 下 seat.id)',
    seat_id_2  BIGINT NOT NULL
               COMMENT '一对里的第二个座位, 必须与 seat_id_1 同排且 x 相邻',
    label      VARCHAR(16) DEFAULT NULL
               COMMENT '可选显示名, 例 "情侣5排"',
    UNIQUE KEY uk_version_seat1 (version_id, seat_id_1)
        COMMENT '一个座位最多属于一对',
    UNIQUE KEY uk_version_seat2 (version_id, seat_id_2),
    KEY idx_version (version_id),
    KEY idx_hall (hall_id),
    KEY idx_seat1 (seat_id_1),
    KEY idx_seat2 (seat_id_2)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='情侣对绑定: 两个物理座位 + 后端绑定';

SET sql_notes = 1;
