CREATE TABLE IF NOT EXISTS company_data_update_member (
 task_id VARCHAR(36) PRIMARY KEY, group_id VARCHAR(36) NOT NULL, KEY idx_update_member_group(group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
