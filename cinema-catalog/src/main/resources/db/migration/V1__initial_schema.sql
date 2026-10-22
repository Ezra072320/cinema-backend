
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
CREATE TABLE `type` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(64) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `movie` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `title` varchar(255) NOT NULL,
  `summary` text,
  `score` decimal(3,1) DEFAULT '0.0',
  `release_date` date DEFAULT NULL,
  `area` varchar(64) DEFAULT NULL,
  `status` tinyint DEFAULT '1' COMMENT '0 经典 / 1 热映 / 2 即将',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `original_title` varchar(255) DEFAULT NULL COMMENT '英文名 / 原名',
  `language` varchar(32) DEFAULT NULL COMMENT '主语言',
  `runtime_min` int NOT NULL DEFAULT '0' COMMENT '时长(分钟)；0 = 待映影片尚未公布时长',
  `poster_url` varchar(512) DEFAULT NULL COMMENT '海报原图',
  `backdrop_url` varchar(512) DEFAULT NULL COMMENT '背景图',
  `trailer_url` varchar(512) DEFAULT NULL COMMENT '预告片 mp4',
  `cat` varchar(255) DEFAULT NULL COMMENT '类型 csv (猫眼 cat)',
  `dir` varchar(255) DEFAULT NULL COMMENT '导演 csv (猫眼 dir)',
  `source` varchar(32) DEFAULT 'MANUAL' COMMENT 'MANUAL / MAOYAN',
  `maoyan_id` int DEFAULT NULL COMMENT '猫眼影片 ID',
  `show_days` int DEFAULT '0' COMMENT '上映天数',
  `first_day_box` decimal(12,2) DEFAULT '0.00' COMMENT '首日票房',
  `total_box` decimal(14,2) DEFAULT '0.00' COMMENT '累计票房',
  `fmt` varchar(32) DEFAULT NULL COMMENT '制式 (3D IMAX...)',
  `wish_count` int DEFAULT '0' COMMENT '想看人数',
  `score_count` int DEFAULT '0' COMMENT '评分数',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `score_source` varchar(16) DEFAULT NULL COMMENT '评分来源，和资料来源分开',
  `last_synced_at` datetime DEFAULT NULL COMMENT '最近一次成功同步资料的时间',
  `manual_fields` varchar(255) NOT NULL DEFAULT '' COMMENT '人工确认并保护的字段键',
  `manual_revision` int NOT NULL DEFAULT '0' COMMENT '人工编辑版本，防止覆盖其他管理员修改',
  `last_repair_at` datetime DEFAULT NULL COMMENT '上次尝试补缺时间',
  `tmdb_id` int DEFAULT NULL COMMENT 'TMDB movie ID',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_movie_maoyan_id` (`maoyan_id`),
  UNIQUE KEY `uk_movie_tmdb_id` (`tmdb_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `movie_to_type` (
  `movie_id` bigint NOT NULL,
  `type_id` bigint NOT NULL,
  PRIMARY KEY (`movie_id`,`type_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_want_movie` (
  `user_id` bigint NOT NULL,
  `movie_id` bigint NOT NULL,
  PRIMARY KEY (`user_id`,`movie_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `person` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(128) NOT NULL,
  `original_name` varchar(128) DEFAULT NULL,
  `gender` tinyint DEFAULT '0' COMMENT '0 未知 / 1 男 / 2 女',
  `birth` date DEFAULT NULL,
  `death` date DEFAULT NULL,
  `biography` text,
  `profile_url` varchar(512) DEFAULT NULL,
  `imdb_id` varchar(32) DEFAULT NULL,
  `maoyan_id` int DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `tmdb_id` int DEFAULT NULL COMMENT 'TMDB person ID',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_person_imdb` (`imdb_id`),
  KEY `idx_person_name` (`name`),
  KEY `idx_person_tmdb_id` (`tmdb_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `movie_crew` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `movie_id` bigint NOT NULL,
  `person_id` bigint NOT NULL,
  `role_type` tinyint NOT NULL COMMENT '1 ACTOR / 2 DIRECTOR / 3 SCREENWRITER',
  `character_name` varchar(128) DEFAULT NULL COMMENT '饰演角色(仅 ACTOR)',
  `job_name` varchar(128) DEFAULT NULL COMMENT '职务原文 (导演/编剧)',
  `sort_order` int DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_crew_movie` (`movie_id`),
  KEY `idx_crew_person` (`person_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `movie_image` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `movie_id` bigint NOT NULL,
  `kind` tinyint NOT NULL COMMENT '1 POSTER / 2 BACKDROP / 3 STILL',
  `url` varchar(512) NOT NULL,
  `width` int DEFAULT NULL,
  `height` int DEFAULT NULL,
  `language` varchar(16) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_image_movie` (`movie_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `movie_video` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `movie_id` bigint NOT NULL,
  `site` varchar(16) DEFAULT 'mp4',
  `video_key` varchar(128) NOT NULL,
  `name` varchar(255) DEFAULT NULL,
  `video_type` varchar(32) DEFAULT 'Trailer',
  `official` tinyint(1) DEFAULT '0',
  `published_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_video_movie` (`movie_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `movie_tag` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `movie_id` bigint NOT NULL,
  `tag` varchar(64) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_movie_tag` (`movie_id`,`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `movie_alt_title` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `movie_id` bigint NOT NULL,
  `title` varchar(255) NOT NULL,
  `country` varchar(64) DEFAULT NULL,
  `language` varchar(64) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_alt_title` (`movie_id`,`title`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

