-- Provider connections are independent of subscriptions and future import/export jobs.
CREATE TABLE IF NOT EXISTS company_integration_connection (
    provider VARCHAR(32) NOT NULL,
    connection_key VARCHAR(64) NOT NULL,
    config_json MEDIUMTEXT NOT NULL,
    secret_cipher MEDIUMTEXT NOT NULL,
    updated_at BIGINT NOT NULL,
    updated_by BIGINT NULL,
    PRIMARY KEY (provider, connection_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
