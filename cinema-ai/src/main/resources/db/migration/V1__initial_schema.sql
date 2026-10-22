
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
CREATE TABLE `ai_session` (
  `chat_id` varchar(64) NOT NULL COMMENT '会话ID(前端生成的UUID)',
  `user_id` bigint NOT NULL COMMENT '归属用户ID',
  `title` varchar(120) NOT NULL DEFAULT '新对话' COMMENT '会话标题=首条提问截断',
  `last_message` varchar(255) DEFAULT NULL COMMENT '最近一条消息摘要(回复优先, 否则提问)',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_time` datetime NOT NULL COMMENT '最近活动时间',
  PRIMARY KEY (`chat_id`),
  KEY `idx_ai_session_user` (`user_id`,`update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI客服会话';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ai_message` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `chat_id` varchar(64) NOT NULL COMMENT '所属会话',
  `user_id` bigint NOT NULL COMMENT '归属用户(冗余, 级联删除免联表)',
  `role` varchar(16) NOT NULL COMMENT 'user / assistant',
  `content` mediumtext NOT NULL COMMENT '消息正文',
  `create_time` datetime(3) NOT NULL COMMENT '发生时间(毫秒精度)',
  PRIMARY KEY (`id`),
  KEY `idx_ai_message_chat` (`chat_id`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI客服消息';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ai_action` (
  `id` varchar(64) NOT NULL,
  `user_id` bigint NOT NULL,
  `chat_id` varchar(64) NOT NULL,
  `type` varchar(32) NOT NULL,
  `title` varchar(120) NOT NULL,
  `summary` text NOT NULL,
  `payload` text NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'PENDING',
  `result` text,
  `path` varchar(255) DEFAULT NULL,
  `create_time` datetime NOT NULL,
  `expires_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_ai_action_session` (`chat_id`,`user_id`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AIå®¢æœå¾…ç¡®è®¤æ“ä½œä¸Žæ‰§è¡Œç»“æžœ';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

