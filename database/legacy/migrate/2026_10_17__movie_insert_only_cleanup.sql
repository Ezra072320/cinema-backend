-- 影片库「只新增」重构清理：
--   1) 遗留表 actor / movie_role 数据早已迁入 person / movie_crew，连同死代码一起下线；
--   2) movie 表删 4 个冗余列：slug（无读方）、tagline（恒 NULL）、hot_value（活链路不写、前端不展示）、
--      douban_id（豆瓣片库链路下线后无消费方，156 部豆瓣来源影片的豆瓣 ID 随之丢弃，已确认可接受）。
-- 幂等：重复执行不报错。
SET NAMES utf8mb4;
DROP PROCEDURE IF EXISTS _cinema_movie_cleanup;
DELIMITER //
CREATE PROCEDURE _cinema_movie_cleanup()
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='movie_role') THEN
        DROP TABLE movie_role;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='actor') THEN
        DROP TABLE actor;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='movie' AND index_name='uk_movie_slug') THEN
        ALTER TABLE movie DROP INDEX uk_movie_slug;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='movie' AND index_name='uk_movie_douban_id') THEN
        ALTER TABLE movie DROP INDEX uk_movie_douban_id;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='movie' AND column_name='slug') THEN
        ALTER TABLE movie DROP COLUMN slug;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='movie' AND column_name='tagline') THEN
        ALTER TABLE movie DROP COLUMN tagline;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='movie' AND column_name='hot_value') THEN
        ALTER TABLE movie DROP COLUMN hot_value;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='movie' AND column_name='douban_id') THEN
        ALTER TABLE movie DROP COLUMN douban_id;
    END IF;
END //
DELIMITER ;
CALL _cinema_movie_cleanup();
DROP PROCEDURE _cinema_movie_cleanup;
