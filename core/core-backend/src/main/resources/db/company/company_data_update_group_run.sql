CREATE TABLE IF NOT EXISTS company_data_update_group_run (
 id VARCHAR(36) PRIMARY KEY, group_id VARCHAR(36) NOT NULL, trigger_key CHAR(64) NOT NULL,
 snapshot MEDIUMTEXT NOT NULL, owner_id VARCHAR(128) NOT NULL, business_date DATE NOT NULL,
 status VARCHAR(32) NOT NULL, cancel_requested TINYINT NOT NULL DEFAULT 0,
 message VARCHAR(1000) NOT NULL DEFAULT '', created_at DATETIME(3) NOT NULL, finished_at DATETIME(3) NULL,
 UNIQUE KEY uk_update_group_trigger(group_id,trigger_key), KEY idx_update_group_history(group_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
