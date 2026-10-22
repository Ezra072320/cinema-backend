-- AI 服务单：工具只能拟单，用户确认接口才会执行；可重复执行。
CREATE TABLE IF NOT EXISTS ai_action (
    id          VARCHAR(64) NOT NULL PRIMARY KEY,
    user_id     BIGINT NOT NULL,
    chat_id     VARCHAR(64) NOT NULL,
    type        VARCHAR(32) NOT NULL,
    title       VARCHAR(120) NOT NULL,
    summary     TEXT NOT NULL,
    payload     TEXT NOT NULL,
    status      VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    result      TEXT,
    path        VARCHAR(255),
    create_time DATETIME NOT NULL,
    expires_at  DATETIME NOT NULL,
    KEY idx_ai_action_session (chat_id, user_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI客服待确认操作与执行结果';
