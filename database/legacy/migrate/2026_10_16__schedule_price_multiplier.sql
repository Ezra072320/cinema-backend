-- =============================================================
-- 影厅座位图改造 Phase 6: 票价倍率归场次
-- (2026-10-16)
-- =============================================================
-- 目的:
--   把「VIP 座 3 倍 / 情侣座 2.5 倍」这两个倍率从 Java 代码里挪到 schedule 表,
--   让每个场次能独立定价(早场打折、IMAX 加价、节假日调价都靠它).
--
-- 为什么倍率属于场次而不是座位类型:
--   座位类型(VIP/情侣)是**物理属性**, 这场是 VIP 下 3 小时还是 VIP 下 3 小时都是 VIP;
--   但"VIP 收几倍钱"是**排片时的经营决策**, 同一个厅的午场和午夜场完全可能不一样.
--   原来的写法把两者绑死在 TicketServiceImpl 的 switch 里, 改价要改代码、重新发版.
--
-- 注意:
--   ticket.price 在建票那一刻就算好并落库了, 之后改场次倍率**不会**回头改已有票.
--   这是有意的 —— 已经卖出去的票价格不能被后台改动.
-- =============================================================

SET NAMES utf8mb4;
SET sql_notes = 0;

ALTER TABLE `schedule`
    ADD COLUMN vip_price_multiplier DECIMAL(3,2) NOT NULL DEFAULT 3.00
        COMMENT 'VIP 座票价倍率, 乘基础票价';

ALTER TABLE `schedule`
    ADD COLUMN couple_price_multiplier DECIMAL(3,2) NOT NULL DEFAULT 2.50
        COMMENT '情侣座票价倍率, 乘基础票价';

-- 老场次自动拿到默认值(3.00 / 2.50), 与迁移前代码里硬编码的一致, 行为不变.
SELECT
    (SELECT COUNT(*) FROM `schedule`) AS schedule_count,
    (SELECT COUNT(*) FROM `schedule` WHERE vip_price_multiplier IS NULL) AS missing_vip,
    (SELECT COUNT(*) FROM `schedule` WHERE couple_price_multiplier IS NULL) AS missing_couple;

SET sql_notes = 1;
