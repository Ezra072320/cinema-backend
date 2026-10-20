-- 复制自 db/migrate/2026_10_19__order_refund_and_close.sql，顺序=README 已验证顺序
-- =============================================================
-- 订单退款落账 + 超时关单 (2026-10-19)
-- =============================================================
-- 目的:
-- 1) order 加 refund_amount / refund_time —— 退款真实金额落账
--    (开场 >6h 全额、3-6h 按 70%、<3h 拒退; 此前"部分退款"只是文案).
-- 2) 加 idx_order_status_create(status, create_time) —— 超时关单定时任务
--    每分钟扫 status=1 AND create_time < now-15min, 没索引就是全表扫.
--
-- 状态语义重定义: 4 由「退款中」(死状态, 从未写入过库) 改为「已关闭」
-- (超时自动关单 / 用户手动取消 / 取消场次时的待支付单). 历史无 4 数据, 零成本.
--
-- 可重复执行.
-- =============================================================

SET NAMES utf8mb4;
SET sql_notes = 0;

DROP PROCEDURE IF EXISTS _cinema_order_refund_close;
DELIMITER //
CREATE PROCEDURE _cinema_order_refund_close()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema=DATABASE() AND table_name='order' AND column_name='refund_amount') THEN
        ALTER TABLE `order`
            ADD COLUMN refund_amount DECIMAL(10,2) DEFAULT NULL
                COMMENT '实退金额。全额=total_amount；开场3-6h档=total*0.7；未退款为 NULL';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema=DATABASE() AND table_name='order' AND column_name='refund_time') THEN
        ALTER TABLE `order`
            ADD COLUMN refund_time DATETIME DEFAULT NULL COMMENT '退款时间';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema=DATABASE() AND table_name='order' AND index_name='idx_order_status_create') THEN
        ALTER TABLE `order` ADD INDEX idx_order_status_create (status, create_time);
    END IF;
END //
DELIMITER ;
CALL _cinema_order_refund_close();
DROP PROCEDURE _cinema_order_refund_close;

-- 自检输出: 列与索引应存在
SELECT
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema=DATABASE() AND table_name='order'
       AND column_name IN ('refund_amount','refund_time')) AS new_columns,
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema=DATABASE() AND table_name='order'
       AND index_name='idx_order_status_create') AS new_index;

SET sql_notes = 1;
