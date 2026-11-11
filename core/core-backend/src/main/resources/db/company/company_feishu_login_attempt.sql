CREATE TABLE IF NOT EXISTS company_feishu_login_attempt (
    state_hash CHAR(64) NOT NULL PRIMARY KEY,
    verifier_hash CHAR(64) NOT NULL,
    app_id VARCHAR(64) NOT NULL,
    tenant_key VARCHAR(128) NOT NULL,
    callback_url VARCHAR(1200) NOT NULL,
    login_mode VARCHAR(8) NOT NULL,
    expires_at BIGINT NOT NULL,
    consumed TINYINT NOT NULL DEFAULT 0,
    KEY idx_feishu_attempt_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
