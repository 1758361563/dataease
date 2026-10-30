CREATE TABLE IF NOT EXISTS company_data_update_task (
 id VARCHAR(36) PRIMARY KEY,
 target_key CHAR(64) NOT NULL,
 version BIGINT NOT NULL,
 definition MEDIUMTEXT NOT NULL,
 updated_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_data_update_target (target_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
