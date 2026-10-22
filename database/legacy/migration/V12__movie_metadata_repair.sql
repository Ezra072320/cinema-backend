-- 复制自 db/migrate/2026_10_17__movie_metadata_repair.sql，顺序=README 已验证顺序
-- 仅增加维护字段，不改电影 ID、原资料和业务引用。可重复执行。
SET NAMES utf8mb4;
DROP PROCEDURE IF EXISTS _cinema_metadata_columns;
DELIMITER //
CREATE PROCEDURE _cinema_metadata_columns()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='movie' AND column_name='manual_fields') THEN
        ALTER TABLE movie ADD COLUMN manual_fields VARCHAR(255) NOT NULL DEFAULT '' COMMENT '人工确认并保护的字段键';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='movie' AND column_name='manual_revision') THEN
        ALTER TABLE movie ADD COLUMN manual_revision INT NOT NULL DEFAULT 0 COMMENT '人工编辑版本，防止覆盖其他管理员修改';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='movie' AND column_name='last_repair_at') THEN
        ALTER TABLE movie ADD COLUMN last_repair_at DATETIME NULL COMMENT '上次尝试补缺时间';
    END IF;
END //
DELIMITER ;
CALL _cinema_metadata_columns();
DROP PROCEDURE _cinema_metadata_columns;
