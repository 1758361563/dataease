CREATE TABLE IF NOT EXISTS company_data_update_claim (
 target_key CHAR(64) PRIMARY KEY, owner_id VARCHAR(36) NOT NULL, created_at DATETIME(3) NOT NULL,
 KEY idx_update_claim_owner (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
