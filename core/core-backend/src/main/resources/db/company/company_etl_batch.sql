CREATE TABLE IF NOT EXISTS company_etl_batch (
 planned_at VARCHAR(19) NOT NULL, kind VARCHAR(16) NOT NULL, package_id BIGINT NOT NULL, datasource_id BIGINT NOT NULL,
 window_start VARCHAR(10) NOT NULL, window_end VARCHAR(10) NOT NULL, names_json TEXT NOT NULL,
 trigger_type VARCHAR(16) NOT NULL, single_task TINYINT NOT NULL DEFAULT 0,
 PRIMARY KEY(planned_at,kind)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
