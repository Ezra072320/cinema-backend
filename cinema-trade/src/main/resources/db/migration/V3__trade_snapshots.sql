CREATE TABLE movie_snapshot (
 id BIGINT NOT NULL PRIMARY KEY,
 title VARCHAR(255),
 poster_url VARCHAR(1024),
 runtime_min INT,
 total_box DECIMAL(18,2)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE score ADD COLUMN author_name VARCHAR(64) DEFAULT '观影用户';
