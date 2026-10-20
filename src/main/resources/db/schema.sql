

-- =============================================================
-- 猫头鹰影院翻新版 - 数据库 Schema 参考脚本
-- =============================================================
-- 该脚本仅作结构参考；生产请改用 Flyway / Liquibase 维护版本化 DDL

CREATE DATABASE IF NOT EXISTS cinema DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE cinema;

CREATE TABLE IF NOT EXISTS `user` (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    openid       VARCHAR(64)  COMMENT '微信 openid',
    `name`       VARCHAR(32) NOT NULL UNIQUE,
    `password`   VARCHAR(64)  NOT NULL,
    age          INT          DEFAULT 0,
    pic          VARCHAR(255) DEFAULT '',
    `status`     TINYINT      DEFAULT 1 COMMENT '0 冻结 / 1 正常',
    create_time  DATETIME     DEFAULT CURRENT_TIMESTAMP,
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

CREATE TABLE IF NOT EXISTS `type` (
    id   BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(64) NOT NULL UNIQUE
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

CREATE TABLE IF NOT EXISTS hall (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    `name`    VARCHAR(64) NOT NULL,
    type      TINYINT    DEFAULT 1 COMMENT '1 2D / 2 3D / 3 IMAX2D / 4 IMAX3D',
    capacity  INT        DEFAULT 0
);

CREATE TABLE IF NOT EXISTS seat (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    hall_id  BIGINT NOT NULL,
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
    content   VARCHAR(1024),
    create_time DATETIME NULL,
    update_time DATETIME NULL,
    version BIGINT NOT NULL DEFAULT 1
);
