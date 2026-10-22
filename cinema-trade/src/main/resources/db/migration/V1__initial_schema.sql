
/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `hall` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(64) NOT NULL,
  `type` tinyint DEFAULT '1' COMMENT '1 2D / 2 3D / 3 IMAX2D / 4 IMAX3D',
  `capacity` int DEFAULT '0',
  `current_version_id` bigint DEFAULT NULL COMMENT '当前生效的座位图版本, 指向 hall_seat_version.id',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `seat` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `hall_id` bigint DEFAULT NULL,
  `y` int DEFAULT NULL,
  `x` int DEFAULT NULL,
  `status` tinyint DEFAULT '1',
  `type` tinyint DEFAULT '1' COMMENT '1 标准 / 2 情侣 / 3 VIP',
  `version_id` bigint DEFAULT NULL COMMENT '所属座位图版本, 指向 hall_seat_version.id. NULL = 待回填',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_version_xy` (`version_id`,`y`,`x`),
  KEY `idx_version` (`version_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `schedule` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `movie_id` bigint NOT NULL,
  `hall_id` bigint NOT NULL,
  `start_time` datetime NOT NULL,
  `end_time` datetime NOT NULL,
  `price` decimal(8,2) NOT NULL DEFAULT '50.00',
  `status` tinyint DEFAULT '1',
  `seat_version_id` bigint DEFAULT NULL COMMENT '排片时锁定的座位图版本, 指向 hall_seat_version.id. 场次生命周期内不变',
  `vip_price_multiplier` decimal(3,2) NOT NULL DEFAULT '3.00' COMMENT 'VIP 座票价倍率, 乘基础票价',
  `couple_price_multiplier` decimal(3,2) NOT NULL DEFAULT '2.50' COMMENT '情侣座票价倍率, 乘基础票价',
  `batch_id` varchar(36) DEFAULT NULL COMMENT '批量排片批次号(UUID)。单场排片与历史数据为 NULL',
  PRIMARY KEY (`id`),
  KEY `idx_seat_version` (`seat_version_id`),
  KEY `idx_schedule_batch` (`batch_id`),
  KEY `idx_schedule_hall_start` (`hall_id`,`start_time`),
  KEY `idx_schedule_movie_start` (`movie_id`,`start_time`),
  KEY `idx_schedule_movie_status_end` (`movie_id`,`status`,`end_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ticket` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `schedule_id` bigint NOT NULL,
  `seat_id` bigint NOT NULL,
  `order_id` bigint DEFAULT '-1',
  `status` tinyint DEFAULT '1' COMMENT '0 已占用 / 1 可售 / 2 已退',
  `price` decimal(8,2) DEFAULT '0.00',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_schedule_seat` (`schedule_id`,`seat_id`),
  KEY `idx_schedule` (`schedule_id`),
  KEY `idx_ticket_order_status` (`order_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `order` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `number` varchar(64) NOT NULL,
  `user_id` bigint NOT NULL,
  `schedule_id` bigint NOT NULL,
  `total_amount` decimal(10,2) NOT NULL,
  `status` tinyint DEFAULT '1',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `refund_amount` decimal(10,2) DEFAULT NULL COMMENT '实退金额。全额=total_amount；开场3-6h档=total*0.7；未退款为 NULL',
  `refund_time` datetime DEFAULT NULL COMMENT '退款时间',
  `pay_channel` varchar(32) DEFAULT NULL COMMENT '支付渠道(模拟): WECHAT/ALIPAY/CARD',
  `pay_time` datetime DEFAULT NULL COMMENT '支付时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_number` (`number`),
  KEY `idx_order_status_create` (`status`,`create_time`),
  KEY `idx_order_user_status_create` (`user_id`,`status`,`create_time`),
  KEY `idx_order_schedule` (`schedule_id`),
  KEY `idx_order_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `score` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `movie_id` bigint NOT NULL,
  `order_id` bigint NOT NULL,
  `score` int DEFAULT NULL,
  `content` varchar(1024) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `version` bigint NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `order_id` (`order_id`),
  UNIQUE KEY `uk_score_order` (`order_id`),
  KEY `idx_score_movie_time` (`movie_id`,`update_time`,`id`),
  KEY `idx_score_user_time` (`user_id`,`update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `hall_seat_version` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `hall_id` bigint NOT NULL COMMENT '所属影厅',
  `version` int NOT NULL COMMENT '版本号, 从 1 起, 同 hall 自增',
  `template_id` bigint DEFAULT NULL COMMENT '基于哪个模板. NULL = 完全自定义(暂未支持)',
  `applied_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发布时间',
  `applied_by` bigint DEFAULT NULL COMMENT '发布人 admin id',
  `note` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注, 例「维修调整」「情人节加情侣位」',
  `is_current` tinyint NOT NULL DEFAULT '0' COMMENT '1=当前生效, 同 hall 仅 1 条=1',
  `is_locked` tinyint NOT NULL DEFAULT '0' COMMENT '1=冻结, 永不能改不能删, 老版本发布后立即变 1',
  `draft_lock_version` int NOT NULL DEFAULT '0' COMMENT '草稿乐观锁版本. 已发布版本保持 0, 草稿每次保存 +1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_hall_version` (`hall_id`,`version`),
  KEY `idx_current` (`hall_id`,`is_current`),
  KEY `idx_locked` (`hall_id`,`is_locked`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='影厅座位图版本快照: 每次发布产生新行, 老行冻结';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `seat_template` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '模板名称, 全局唯一, 例「1号厅标准模板」/「IMAX 巨幕」',
  `description` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注, 例「8 排 10 列, 7 排起为 VIP」',
  `rows_count` int NOT NULL COMMENT '总排数, 与 seat_template_unit.y 的最大值一致',
  `cols_count` int NOT NULL COMMENT '总列数, 物理列号 1..cols_count, 允许中间有 status=0 (过道/留空)',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='座位模板: 草图, 可复用, 不直接绑定到任何影厅';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `seat_template_unit` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `template_id` bigint NOT NULL COMMENT '所属模板, 模板删除时级联删单元(暂时手工级联,见 Service.delete)',
  `y` int NOT NULL COMMENT '排号, 从 1 起',
  `x` int NOT NULL COMMENT '列号, 从 1 起, 物理列号',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '0 无座位(过道) / 1 标准 / 3 VIP / 4 无障碍',
  `label` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '可选显示名, 例 "5排12座" / "VIP-A1". 不填前端按 y/x 自动生成',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_template_xy` (`template_id`,`y`,`x`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模板里的座位单元: 一行一列 = 一行记录';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `seat_couple_group` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `hall_id` bigint NOT NULL COMMENT '所属影厅, 冗余便于按厅查',
  `version_id` bigint NOT NULL COMMENT '所属座位图版本, 指向 hall_seat_version.id',
  `seat_id_1` bigint NOT NULL COMMENT '一对里的第一个座位(hall_seat_version 下 seat.id)',
  `seat_id_2` bigint NOT NULL COMMENT '一对里的第二个座位, 必须与 seat_id_1 同排且 x 相邻',
  `label` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '可选显示名, 例 "情侣5排"',
  `seat_a` bigint GENERATED ALWAYS AS (least(`seat_id_1`,`seat_id_2`)) STORED,
  `seat_b` bigint GENERATED ALWAYS AS (greatest(`seat_id_1`,`seat_id_2`)) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_version_seat1` (`version_id`,`seat_id_1`) COMMENT '一个座位最多属于一对',
  UNIQUE KEY `uk_version_seat2` (`version_id`,`seat_id_2`),
  UNIQUE KEY `uk_couple_seat_a` (`version_id`,`seat_a`),
  UNIQUE KEY `uk_couple_seat_b` (`version_id`,`seat_b`),
  KEY `idx_version` (`version_id`),
  KEY `idx_hall` (`hall_id`),
  KEY `idx_seat1` (`seat_id_1`),
  KEY `idx_seat2` (`seat_id_2`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='情侣对绑定: 两个物理座位 + 后端绑定';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `order_action` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL COMMENT '订单id',
  `order_number` varchar(64) NOT NULL COMMENT '订单号(冗余,便于检索)',
  `action` varchar(32) NOT NULL COMMENT 'CREATE/PAY/REFUND/CLOSE/CANCEL_REFUND',
  `from_status` int DEFAULT NULL COMMENT '变更前状态',
  `to_status` int DEFAULT NULL COMMENT '变更后状态',
  `amount` decimal(10,2) DEFAULT NULL COMMENT '涉及金额(支付额/实退额)',
  `operator_type` varchar(16) NOT NULL COMMENT 'USER/ADMIN/SYSTEM',
  `operator_id` bigint DEFAULT NULL COMMENT '操作者id,系统动作为NULL',
  `reason` varchar(255) DEFAULT NULL COMMENT '原因(超时关闭/取消场次/用户退款等)',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_order_action_order` (`order_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单资金操作审计流水';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `admin_operation_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `admin_id` bigint DEFAULT NULL COMMENT '操作管理员id',
  `admin_name` varchar(64) DEFAULT NULL,
  `action` varchar(64) NOT NULL COMMENT 'SCHEDULE_CANCEL等',
  `target_type` varchar(32) DEFAULT NULL COMMENT 'SCHEDULE/ORDER/...',
  `target_id` bigint DEFAULT NULL,
  `detail` varchar(1024) DEFAULT NULL COMMENT '结构化摘要(退款笔数/金额等)',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_aol_admin_time` (`admin_id`,`created_at`),
  KEY `idx_aol_target` (`target_type`,`target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='高风险管理操作日志';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

