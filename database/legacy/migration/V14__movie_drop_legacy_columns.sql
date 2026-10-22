-- 复制自 db/migrate/2026_10_17__movie_drop_legacy_columns.sql，顺序=README 已验证顺序
-- movie 表删冗余列 + 时长约定变更：
--   1) `time` 与 `runtime_min` 双写冗余，统一为 runtime_min；`pic` 与 `poster_url` 双写冗余，统一为 poster_url。
--   2) 新约定：runtime_min = 0 表示「待映影片，时长未公布」，是合法状态而非缺失资料；
--      因此列为 NOT NULL DEFAULT 0，已上映影片的 0 时长才是「资料待补」。
-- 幂等：重复执行不报错。
SET NAMES utf8mb4;
UPDATE movie SET runtime_min = COALESCE(runtime_min, time, 0) WHERE runtime_min IS NULL OR runtime_min = 0;
UPDATE movie SET poster_url = COALESCE(NULLIF(poster_url, ''), pic) WHERE (poster_url IS NULL OR poster_url = '') AND pic IS NOT NULL AND pic != '';
DROP PROCEDURE IF EXISTS _cinema_movie_drop_legacy;
DELIMITER //
CREATE PROCEDURE _cinema_movie_drop_legacy()
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='movie' AND column_name='time') THEN
        ALTER TABLE movie DROP COLUMN time;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='movie' AND column_name='pic') THEN
        ALTER TABLE movie DROP COLUMN pic;
    END IF;
    ALTER TABLE movie MODIFY COLUMN runtime_min INT NOT NULL DEFAULT 0 COMMENT '时长(分钟)；0 = 待映影片尚未公布时长';
END //
DELIMITER ;
CALL _cinema_movie_drop_legacy();
DROP PROCEDURE _cinema_movie_drop_legacy;
