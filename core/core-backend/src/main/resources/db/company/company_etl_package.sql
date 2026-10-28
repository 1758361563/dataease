CREATE TABLE IF NOT EXISTS company_etl_package (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, package_kind VARCHAR(16) NOT NULL, run_kind VARCHAR(16) NOT NULL,
 file_sha256 CHAR(64) NOT NULL, source_commit VARCHAR(40), generated_at VARCHAR(19), manifest_json LONGTEXT NOT NULL,
 imported_by BIGINT NOT NULL, imported_at VARCHAR(19) NOT NULL, status VARCHAR(16) NOT NULL,
 reject_reason VARCHAR(512), init_status VARCHAR(16) NOT NULL DEFAULT 'pending', archive_bytes LONGBLOB NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
