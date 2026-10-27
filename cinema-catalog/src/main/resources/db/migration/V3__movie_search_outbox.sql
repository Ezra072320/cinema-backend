CREATE TABLE event_outbox (
    event_id VARCHAR(64) NOT NULL PRIMARY KEY,
    topic VARCHAR(80) NOT NULL,
    event_tag VARCHAR(64) NOT NULL,
    payload LONGTEXT NOT NULL,
    delivery_timestamp BIGINT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at BIGINT NOT NULL,
    lease_token VARCHAR(36) NULL,
    lease_until BIGINT NULL,
    last_error VARCHAR(128) NULL,
    created_at BIGINT NOT NULL,
    sent_at BIGINT NULL,
    KEY idx_outbox_pending (status, next_attempt_at),
    KEY idx_outbox_lease (status, lease_until),
    CONSTRAINT chk_outbox_payload CHECK (JSON_VALID(payload))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE movie_search_clock (id INT PRIMARY KEY, version BIGINT NOT NULL);
INSERT INTO movie_search_clock VALUES (1,0);
CREATE TABLE movie_search_document (
  movie_id BIGINT PRIMARY KEY,
  version BIGINT NOT NULL,
  hidden BOOLEAN NOT NULL DEFAULT FALSE,
  payload LONGTEXT NOT NULL,
  UNIQUE KEY uk_search_source_version(version),
  CONSTRAINT ck_search_payload CHECK(JSON_VALID(payload))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
