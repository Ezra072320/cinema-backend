-- 复制自 db/migrate/2026_10_19__ai_chat_persist.sql，顺序=README 已验证顺序
-- =============================================================
-- AI 客服会话/消息持久化 (2026-10-19)
-- =============================================================
-- 目的:
-- 1) 会话列表与聊天历史原存 JVM 内存(ConcurrentHashMap + MessageWindowChatMemory),
--    服务一重启全丢, 侧栏标题/时间全靠前端编造(且与后端返回字段对不上, 永远显示"新对话").
-- 2) 迁到 MySQL 后: 重启不丢、标题取首问截断、createTime/lastMessage 落库为真值,
--    且 history 能做归属校验(内存实现里 chatId 是裸 key, 谁都能读).
--
-- 可重复执行(CREATE TABLE IF NOT EXISTS).
-- =============================================================

SET NAMES utf8mb4;
SET sql_notes = 0;

CREATE TABLE IF NOT EXISTS ai_session (
    chat_id      VARCHAR(64)  NOT NULL COMMENT '会话ID(前端生成的UUID)',
    user_id      BIGINT       NOT NULL COMMENT '归属用户ID',
    title        VARCHAR(120) NOT NULL DEFAULT '新对话' COMMENT '会话标题=首条提问截断',
    last_message VARCHAR(255) DEFAULT NULL COMMENT '最近一条消息摘要(回复优先, 否则提问)',
    create_time  DATETIME     NOT NULL COMMENT '创建时间',
    update_time  DATETIME     NOT NULL COMMENT '最近活动时间',
    PRIMARY KEY (chat_id),
    KEY idx_ai_session_user (user_id, update_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AI客服会话';

CREATE TABLE IF NOT EXISTS ai_message (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    chat_id     VARCHAR(64)  NOT NULL COMMENT '所属会话',
    user_id     BIGINT       NOT NULL COMMENT '归属用户(冗余, 级联删除免联表)',
    role        VARCHAR(16)  NOT NULL COMMENT 'user / assistant',
    content     MEDIUMTEXT   NOT NULL COMMENT '消息正文',
    create_time DATETIME(3)  NOT NULL COMMENT '发生时间(毫秒精度)',
    PRIMARY KEY (id),
    KEY idx_ai_message_chat (chat_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AI客服消息';

-- 自检输出: 两表应各计数 1
SELECT
    (SELECT COUNT(*) FROM information_schema.tables
     WHERE table_schema = DATABASE() AND table_name IN ('ai_session', 'ai_message')) AS new_tables;

SET sql_notes = 1;
