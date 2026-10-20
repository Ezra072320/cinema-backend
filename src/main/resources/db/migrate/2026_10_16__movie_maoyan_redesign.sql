-- =============================================================
-- 猫头鹰影院 - 影片模块翻新 (2026-10-16)
-- =============================================================
-- 本脚本执行两件事:
-- 1) 在空库上初始化 cinema schema (idempotent via IF NOT EXISTS).
-- 2) 翻新 movie 表 + 卫星表(person/movie_crew/movie_image/movie_video/movie_tag/movie_alt_title),
--    把老 actor / movie_role 数据迁到 person + movie_crew(role_type=1).
--
-- 设计:
--  - 数据模型按猫眼电影详情页公开字段设计,详见 README "影片管理模块 - 猫眼数据接入".
--  - 旧表 (actor / movie_role / movie_type / movie_to_type / user_want_movie) 保留不动,
--    旧 Service 代码继续可读 (向前兼容), 新代码走 person + movie_crew.
--  - 全部 DDL 幂等 (重跑无害).

SET NAMES utf8mb4;
SET sql_notes = 0;

-- =============================================================
-- 0. 库与基础表 (空库初始化; 已存在则跳过)
-- =============================================================

CREATE DATABASE IF NOT EXISTS cinema DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE cinema;

CREATE TABLE IF NOT EXISTS `user` (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    openid       VARCHAR(64),
    `name`       VARCHAR(32) NOT NULL UNIQUE,
    `password`   VARCHAR(64) NOT NULL,
    age          INT         DEFAULT 0,
    pic          VARCHAR(255) DEFAULT '',
    `status`     TINYINT     DEFAULT 1 COMMENT '0 冻结 / 1 正常',
    create_time  DATETIME    DEFAULT CURRENT_TIMESTAMP,
    last_access_time DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS admin (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    `name`       VARCHAR(32) NOT NULL UNIQUE,
    `password`   VARCHAR(64) NOT NULL,
    age          INT         DEFAULT 0,
    pic          VARCHAR(255) DEFAULT '',
    `status`     TINYINT     DEFAULT 1,
    create_time  DATETIME    DEFAULT CURRENT_TIMESTAMP,
    last_access_time DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS `type` (
    id   BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(64) NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS hall (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    `name`    VARCHAR(64) NOT NULL,
    type      TINYINT     DEFAULT 1 COMMENT '1 2D / 2 3D / 3 IMAX2D / 4 IMAX3D',
    capacity  INT         DEFAULT 0
);

CREATE TABLE IF NOT EXISTS seat (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    hall_id  BIGINT,
    y        INT,
    x        INT,
    status   TINYINT DEFAULT 1,
    type     TINYINT DEFAULT 1 COMMENT '1 标准 / 2 情侣 / 3 VIP'
);

CREATE TABLE IF NOT EXISTS `schedule` (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    movie_id    BIGINT NOT NULL,
    hall_id     BIGINT NOT NULL,
    start_time  DATETIME NOT NULL,
    end_time    DATETIME NOT NULL,
    price       DECIMAL(8,2) NOT NULL DEFAULT 50.00,
    `status`    TINYINT DEFAULT 1
);

CREATE TABLE IF NOT EXISTS ticket (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    schedule_id BIGINT NOT NULL,
    seat_id     BIGINT NOT NULL,
    order_id    BIGINT DEFAULT -1,
    status      TINYINT DEFAULT 1 COMMENT '0 已占用 / 1 可售 / 2 已退',
    price       DECIMAL(8,2) DEFAULT 0
);

CREATE TABLE IF NOT EXISTS `order` (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    number        VARCHAR(64) NOT NULL,
    user_id       BIGINT NOT NULL,
    schedule_id   BIGINT NOT NULL,
    total_amount  DECIMAL(10,2) NOT NULL,
    `status`      TINYINT DEFAULT 1,
    create_time   DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS score (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id   BIGINT NOT NULL,
    movie_id  BIGINT NOT NULL,
    order_id  BIGINT NOT NULL UNIQUE,
    score     INT,
    content   VARCHAR(1024)
);

-- =============================================================
-- 1. 老 movie / actor / movie_role / movie_to_type / user_want_movie
-- =============================================================

CREATE TABLE IF NOT EXISTS movie (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    title        VARCHAR(255) NOT NULL,
    pic          VARCHAR(255) DEFAULT '',
    summary      TEXT,
    `time`       INT          DEFAULT 0,
    score        DECIMAL(3,1) DEFAULT 0,
    release_date DATE,
    area         VARCHAR(64),
    `status`     TINYINT      DEFAULT 1 COMMENT '0 经典 / 1 热映 / 2 即将',
    create_time  DATETIME     DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS actor (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    actor_name VARCHAR(64) NOT NULL,
    post       VARCHAR(255) DEFAULT '',
    pic        VARCHAR(255) DEFAULT ''
);

CREATE TABLE IF NOT EXISTS movie_role (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    movie_id          BIGINT NOT NULL,
    actor_id          BIGINT NOT NULL,
    role_name         VARCHAR(128),
    role_description  VARCHAR(512)
);

CREATE TABLE IF NOT EXISTS movie_to_type (
    movie_id BIGINT NOT NULL,
    type_id  BIGINT NOT NULL,
    PRIMARY KEY (movie_id, type_id)
);

CREATE TABLE IF NOT EXISTS user_want_movie (
    user_id  BIGINT NOT NULL,
    movie_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, movie_id)
);

-- =============================================================
-- 2. 翻新 movie 表 (新增猫眼相关字段, 幂等)
-- =============================================================

DROP PROCEDURE IF EXISTS _add_col_if_missing;
DELIMITER //
CREATE PROCEDURE _add_col_if_missing(
  IN p_table VARCHAR(64),
  IN p_col   VARCHAR(64),
  IN p_ddl   VARCHAR(255)
)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME   = p_table
      AND COLUMN_NAME  = p_col
  ) THEN
    SET @sql = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN ', p_col, ' ', p_ddl);
    PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  END IF;
END //
DELIMITER ;

CALL _add_col_if_missing('movie', 'original_title', "VARCHAR(255) DEFAULT NULL COMMENT '英文名 / 原名'");
CALL _add_col_if_missing('movie', 'slug',           "VARCHAR(160) DEFAULT NULL COMMENT 'URL slug'");
CALL _add_col_if_missing('movie', 'tagline',        "VARCHAR(512) DEFAULT NULL COMMENT '一句话宣传语'");
CALL _add_col_if_missing('movie', 'language',       "VARCHAR(32)  DEFAULT NULL COMMENT '主语言'");
CALL _add_col_if_missing('movie', 'runtime_min',    "INT          DEFAULT NULL COMMENT '时长(分钟)'");
CALL _add_col_if_missing('movie', 'poster_url',     "VARCHAR(512) DEFAULT NULL COMMENT '海报原图'");
CALL _add_col_if_missing('movie', 'backdrop_url',   "VARCHAR(512) DEFAULT NULL COMMENT '背景图'");
CALL _add_col_if_missing('movie', 'trailer_url',    "VARCHAR(512) DEFAULT NULL COMMENT '预告片 mp4'");
CALL _add_col_if_missing('movie', 'cat',            "VARCHAR(255) DEFAULT NULL COMMENT '类型 csv (猫眼 cat)'");
CALL _add_col_if_missing('movie', 'dir',            "VARCHAR(255) DEFAULT NULL COMMENT '导演 csv (猫眼 dir)'");
CALL _add_col_if_missing('movie', 'source',         "VARCHAR(32)  DEFAULT 'MANUAL' COMMENT 'MANUAL / MAOYAN'");
CALL _add_col_if_missing('movie', 'maoyan_id',      "INT          DEFAULT NULL COMMENT '猫眼影片 ID'");
CALL _add_col_if_missing('movie', 'hot_value',      "INT          DEFAULT 0 COMMENT '猫眼热度'");
CALL _add_col_if_missing('movie', 'show_days',      "INT          DEFAULT 0 COMMENT '上映天数'");
CALL _add_col_if_missing('movie', 'first_day_box',  "DECIMAL(12,2) DEFAULT 0 COMMENT '首日票房'");
CALL _add_col_if_missing('movie', 'total_box',      "DECIMAL(14,2) DEFAULT 0 COMMENT '累计票房'");
CALL _add_col_if_missing('movie', 'fmt',            "VARCHAR(32)  DEFAULT NULL COMMENT '制式 (3D IMAX...)'");
CALL _add_col_if_missing('movie', 'wish_count',     "INT          DEFAULT 0 COMMENT '想看人数'");
CALL _add_col_if_missing('movie', 'score_count',    "INT          DEFAULT 0 COMMENT '评分数'");
CALL _add_col_if_missing('movie', 'update_time',    "DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP");

-- 唯一索引 uk_movie_slug (幂等)
SET @idx_exists := (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'movie' AND INDEX_NAME = 'uk_movie_slug'
);
SET @sql := IF(@idx_exists = 0,
  'ALTER TABLE `movie` ADD UNIQUE KEY uk_movie_slug (slug)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 兼容旧 time 列 -> runtime_min
UPDATE movie SET runtime_min = `time` WHERE runtime_min IS NULL AND `time` IS NOT NULL;

-- =============================================================
-- 3. 卫星表 (person / movie_crew / movie_image / movie_video / movie_tag / movie_alt_title)
-- =============================================================

CREATE TABLE IF NOT EXISTS person (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(128) NOT NULL,
    original_name VARCHAR(128) DEFAULT NULL,
    gender        TINYINT      DEFAULT 0 COMMENT '0 未知 / 1 男 / 2 女',
    birth         DATE         DEFAULT NULL,
    death         DATE         DEFAULT NULL,
    biography     TEXT,
    profile_url   VARCHAR(512) DEFAULT NULL,
    imdb_id       VARCHAR(32)  DEFAULT NULL,
    maoyan_id     INT          DEFAULT NULL,
    create_time   DATETIME     DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_person_imdb (imdb_id),
    KEY idx_person_name (name)
);

CREATE TABLE IF NOT EXISTS movie_crew (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    movie_id        BIGINT NOT NULL,
    person_id       BIGINT NOT NULL,
    role_type       TINYINT NOT NULL COMMENT '1 ACTOR / 2 DIRECTOR / 3 SCREENWRITER',
    character_name  VARCHAR(128) DEFAULT NULL COMMENT '饰演角色(仅 ACTOR)',
    job_name        VARCHAR(128) DEFAULT NULL COMMENT '职务原文 (导演/编剧)',
    sort_order      INT          DEFAULT 0,
    KEY idx_crew_movie (movie_id),
    KEY idx_crew_person (person_id)
);

CREATE TABLE IF NOT EXISTS movie_image (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    movie_id   BIGINT NOT NULL,
    kind       TINYINT NOT NULL COMMENT '1 POSTER / 2 BACKDROP / 3 STILL',
    url        VARCHAR(512) NOT NULL,
    width      INT,
    height     INT,
    language   VARCHAR(16) DEFAULT NULL,
    KEY idx_image_movie (movie_id)
);

CREATE TABLE IF NOT EXISTS movie_video (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    movie_id     BIGINT NOT NULL,
    site         VARCHAR(16)  DEFAULT 'mp4',
    video_key    VARCHAR(128) NOT NULL,
    name         VARCHAR(255) DEFAULT NULL,
    video_type   VARCHAR(32)  DEFAULT 'Trailer',
    official     TINYINT(1)   DEFAULT 0,
    published_at DATETIME     DEFAULT NULL,
    KEY idx_video_movie (movie_id)
);

CREATE TABLE IF NOT EXISTS movie_tag (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    movie_id   BIGINT NOT NULL,
    tag        VARCHAR(64) NOT NULL,
    UNIQUE KEY uk_movie_tag (movie_id, tag)
);

CREATE TABLE IF NOT EXISTS movie_alt_title (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    movie_id  BIGINT NOT NULL,
    title     VARCHAR(255) NOT NULL,
    country   VARCHAR(64) DEFAULT NULL,
    language  VARCHAR(64) DEFAULT NULL,
    UNIQUE KEY uk_alt_title (movie_id, title)
);

-- =============================================================
-- 4. 老数据迁移: actor + movie_role -> person + movie_crew(ACTOR)
-- =============================================================

-- 4.1 把老 actor 表里 distinct 名字灌入 person (一次)
INSERT IGNORE INTO person (name, profile_url)
SELECT DISTINCT a.actor_name, NULLIF(a.pic, '')
FROM actor a
WHERE NOT EXISTS (
  SELECT 1 FROM person p WHERE p.name = a.actor_name
);

-- 4.2 把老 movie_role 记录迁移到 movie_crew, role_type=1 (ACTOR)
INSERT IGNORE INTO movie_crew (movie_id, person_id, role_type, character_name, job_name, sort_order)
SELECT
  mr.movie_id,
  p.id,
  1,
  mr.role_name,
  NULL,
  mr.id
FROM movie_role mr
JOIN actor a ON a.id = mr.actor_id
JOIN person p ON p.name = a.actor_name;

-- =============================================================
-- 5. 清理
-- =============================================================

DROP PROCEDURE _add_col_if_missing;

SET sql_notes = 1;

-- =============================================================
-- 验证用 (单独跑)
-- =============================================================
-- SELECT COLUMN_NAME, DATA_TYPE, COLUMN_COMMENT
--   FROM INFORMATION_SCHEMA.COLUMNS
--   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'movie'
--   ORDER BY ORDINAL_POSITION;
-- SELECT COUNT(*) AS person_count FROM person;
-- SELECT COUNT(*) AS movie_crew_count FROM movie_crew;
