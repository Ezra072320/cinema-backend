-- 复制自 db/migrate/2026_10_20__review_module.sql，顺序=README 已验证顺序
-- 评价模块：幂等扩展，不伪造历史发表时间。
SET @review_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='score' AND column_name='create_time')=0,
 'ALTER TABLE score ADD COLUMN create_time DATETIME NULL', 'SELECT 1');
PREPARE review_stmt FROM @review_ddl; EXECUTE review_stmt; DEALLOCATE PREPARE review_stmt;
SET @review_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='score' AND column_name='update_time')=0,
 'ALTER TABLE score ADD COLUMN update_time DATETIME NULL', 'SELECT 1');
PREPARE review_stmt FROM @review_ddl; EXECUTE review_stmt; DEALLOCATE PREPARE review_stmt;
SET @review_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='score' AND column_name='version')=0,
 'ALTER TABLE score ADD COLUMN version BIGINT NOT NULL DEFAULT 1', 'SELECT 1');
PREPARE review_stmt FROM @review_ddl; EXECUTE review_stmt; DEALLOCATE PREPARE review_stmt;
SET @review_ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='score' AND index_name='idx_score_movie_time')=0,
 'CREATE INDEX idx_score_movie_time ON score(movie_id,update_time,id)', 'SELECT 1');
PREPARE review_stmt FROM @review_ddl; EXECUTE review_stmt; DEALLOCATE PREPARE review_stmt;
-- 修复旧逻辑提前评价造成的未来场次“已完成”，恢复其正常已支付状态；保留评价原文。
UPDATE `order` o JOIN schedule s ON s.id=o.schedule_id JOIN score r ON r.order_id=o.id
SET o.status=2 WHERE o.status=6 AND s.status=1 AND s.start_time>NOW();
