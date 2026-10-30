CREATE TABLE IF NOT EXISTS company_data_update_group_node (
 run_id VARCHAR(36) NOT NULL, task_id VARCHAR(36) NOT NULL, status VARCHAR(24) NOT NULL,
 row_count BIGINT NOT NULL DEFAULT 0, message VARCHAR(1000) NOT NULL DEFAULT '',
 started_at DATETIME(3) NULL, finished_at DATETIME(3) NULL, PRIMARY KEY(run_id,task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
