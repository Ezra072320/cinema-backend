-- =============================================================
-- 支付落账 + 资金/管理操作审计 (2026-10-20)
-- =============================================================
-- 目的:
-- 1) order 加 pay_channel / pay_time —— 收款侧对账痕迹（此前只有退款侧落账）
-- 2) order_action —— pay/refund/close/cancel 每笔状态变更留痕（谁、何时、何因、金额）
-- 3) admin_operation_log —— 取消场次等高风险管理操作留痕（操作者/场次/退款笔数）
--
-- 可重复执行。
-- =============================================================

SET NAMES utf8mb4;
SET sql_notes = 0;

DROP PROCEDURE IF EXISTS _cinema_order_payment_audit;
DELIMITER //
CREATE PROCEDURE _cinema_order_payment_audit()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema=DATABASE() AND table_name='order' AND column_name='pay_channel') THEN
        ALTER TABLE `order`
            ADD COLUMN pay_channel VARCHAR(32) DEFAULT NULL COMMENT '支付渠道(模拟): WECHAT/ALIPAY/CARD';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema=DATABASE() AND table_name='order' AND column_name='pay_time') THEN
        ALTER TABLE `order`
            ADD COLUMN pay_time DATETIME DEFAULT NULL COMMENT '支付时间';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.tables
                   WHERE table_schema=DATABASE() AND table_name='order_action') THEN
        CREATE TABLE order_action (
            id BIGINT NOT NULL AUTO_INCREMENT,
            order_id BIGINT NOT NULL COMMENT '订单id',
            order_number VARCHAR(64) NOT NULL COMMENT '订单号(冗余,便于检索)',
            action VARCHAR(32) NOT NULL COMMENT 'CREATE/PAY/REFUND/CLOSE/CANCEL_REFUND',
            from_status INT DEFAULT NULL COMMENT '变更前状态',
            to_status INT DEFAULT NULL COMMENT '变更后状态',
            amount DECIMAL(10,2) DEFAULT NULL COMMENT '涉及金额(支付额/实退额)',
            operator_type VARCHAR(16) NOT NULL COMMENT 'USER/ADMIN/SYSTEM',
            operator_id BIGINT DEFAULT NULL COMMENT '操作者id,系统动作为NULL',
            reason VARCHAR(255) DEFAULT NULL COMMENT '原因(超时关闭/取消场次/用户退款等)',
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (id),
            KEY idx_order_action_order (order_id, created_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单资金操作审计流水';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.tables
                   WHERE table_schema=DATABASE() AND table_name='admin_operation_log') THEN
        CREATE TABLE admin_operation_log (
            id BIGINT NOT NULL AUTO_INCREMENT,
            admin_id BIGINT DEFAULT NULL COMMENT '操作管理员id',
            admin_name VARCHAR(64) DEFAULT NULL,
            action VARCHAR(64) NOT NULL COMMENT 'SCHEDULE_CANCEL等',
            target_type VARCHAR(32) DEFAULT NULL COMMENT 'SCHEDULE/ORDER/...',
            target_id BIGINT DEFAULT NULL,
            detail VARCHAR(1024) DEFAULT NULL COMMENT '结构化摘要(退款笔数/金额等)',
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (id),
            KEY idx_aol_admin_time (admin_id, created_at),
            KEY idx_aol_target (target_type, target_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='高风险管理操作日志';
    END IF;
END //
DELIMITER ;
CALL _cinema_order_payment_audit();
DROP PROCEDURE _cinema_order_payment_audit;

-- 自检输出
SELECT
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema=DATABASE() AND table_name='order'
       AND column_name IN ('pay_channel','pay_time')) AS new_columns,
    (SELECT COUNT(*) FROM information_schema.tables
     WHERE table_schema=DATABASE() AND table_name IN ('order_action','admin_operation_log')) AS new_tables;

SET sql_notes = 1;
