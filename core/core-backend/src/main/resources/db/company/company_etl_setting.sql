CREATE TABLE IF NOT EXISTS company_etl_setting (
 id INT PRIMARY KEY, scheduler_enabled TINYINT NOT NULL DEFAULT 0,
 enabled_instance_id VARCHAR(128), enabled_by BIGINT, enabled_at VARCHAR(19),
 daily_at VARCHAR(5) NOT NULL DEFAULT '03:00', chain_enabled TINYINT NOT NULL DEFAULT 1,
 refresh_datasource_id BIGINT, active_package_id BIGINT, active_test_package_id BIGINT, updated_at VARCHAR(19)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
