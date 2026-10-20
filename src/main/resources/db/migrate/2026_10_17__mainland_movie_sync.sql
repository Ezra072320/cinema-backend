-- 先备份 movie 表。旧版把豆瓣 ID 写进 maoyan_id；两种来源必须分开。
-- 运行方式：mysql cinema < 本文件；脚本可重复执行。
SET NAMES utf8mb4;
DROP PROCEDURE IF EXISTS _cinema_movie_sync_columns;
DELIMITER //
CREATE PROCEDURE _cinema_movie_sync_columns()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='movie' AND column_name='douban_id') THEN
        ALTER TABLE movie ADD COLUMN douban_id INT NULL COMMENT '豆瓣 subject ID';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='movie' AND column_name='score_source') THEN
        ALTER TABLE movie ADD COLUMN score_source VARCHAR(16) NULL COMMENT '评分来源，和资料来源分开';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='movie' AND column_name='last_synced_at') THEN
        ALTER TABLE movie ADD COLUMN last_synced_at DATETIME NULL COMMENT '最近一次成功同步资料的时间';
    END IF;
    -- MANUAL 中仅迁移已核对过的 18 条豆瓣种子，不能猜测其他人工资料的 ID 来源。
    UPDATE movie SET douban_id=maoyan_id, maoyan_id=NULL,
        score_source=IF(score>0, COALESCE(score_source,'DOUBAN'),score_source)
    WHERE maoyan_id IS NOT NULL AND douban_id IS NULL
      AND (source='DOUBAN' OR (source='MANUAL' AND maoyan_id IN
        (36081094,36208094,36369452,36653918,36877322,36154853,34780991,36212391,
         26266893,35267208,35766491,35660795,35267224,36035676,3742360,1889243,35593344,1291561)));
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='movie' AND index_name='uk_movie_maoyan_id') THEN
        ALTER TABLE movie ADD UNIQUE KEY uk_movie_maoyan_id(maoyan_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='movie' AND index_name='uk_movie_douban_id') THEN
        ALTER TABLE movie ADD UNIQUE KEY uk_movie_douban_id(douban_id);
    END IF;
END //
DELIMITER ;
CALL _cinema_movie_sync_columns();
DROP PROCEDURE _cinema_movie_sync_columns;
