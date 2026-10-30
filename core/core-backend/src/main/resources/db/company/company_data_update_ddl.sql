CREATE TABLE IF NOT EXISTS company_data_update_ddl (
 id VARCHAR(36) PRIMARY KEY, target_key CHAR(64) NOT NULL, datasource_id VARCHAR(32) NOT NULL,
 table_name VARCHAR(128) NOT NULL, sql_hash CHAR(64) NOT NULL, sql_text MEDIUMTEXT NOT NULL,
 status VARCHAR(24) NOT NULL, completed_steps INT NOT NULL DEFAULT 0,
 structure_json MEDIUMTEXT NULL, message VARCHAR(1000) NOT NULL DEFAULT '',
 created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL,
 KEY idx_update_ddl_target (target_key,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
