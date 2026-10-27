CREATE TABLE consumed_event (
    event_id VARCHAR(64) NOT NULL PRIMARY KEY,
    payload_hash CHAR(64) NOT NULL,
    received_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE notification (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    event_kind VARCHAR(32) NOT NULL,
    title VARCHAR(100) NOT NULL,
    body VARCHAR(500) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    read_at DATETIME(3) NULL,
    UNIQUE KEY uk_notification_event (event_id),
    KEY idx_notification_user (user_id, id),
    KEY idx_notification_unread (user_id, read_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
