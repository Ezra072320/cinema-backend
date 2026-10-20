-- 复制自 db/migrate/2026_10_17__tmdb_movie_support.sql，顺序=README 已验证顺序
-- 先备份 movie / person 表；来源 ID 独立保存，不改内部主键与业务关联。
SET NAMES utf8mb4;
DROP PROCEDURE IF EXISTS _cinema_tmdb_columns;
DELIMITER //
CREATE PROCEDURE _cinema_tmdb_columns()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='movie' AND column_name='tmdb_id') THEN
        ALTER TABLE movie ADD COLUMN tmdb_id INT NULL COMMENT 'TMDB movie ID';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='person' AND column_name='tmdb_id') THEN
        ALTER TABLE person ADD COLUMN tmdb_id INT NULL COMMENT 'TMDB person ID';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='movie' AND index_name='uk_movie_tmdb_id') THEN
        ALTER TABLE movie ADD UNIQUE KEY uk_movie_tmdb_id(tmdb_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='person' AND index_name='idx_person_tmdb_id') THEN
        ALTER TABLE person ADD INDEX idx_person_tmdb_id(tmdb_id);
    END IF;
END //
DELIMITER ;
CALL _cinema_tmdb_columns();
DROP PROCEDURE _cinema_tmdb_columns;
