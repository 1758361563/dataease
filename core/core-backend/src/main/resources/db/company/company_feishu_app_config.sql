-- Company Feishu app config. Idempotent schema only. Applied by CompanyBiSchemaInitializer.
-- Secret is never stored; app_secret_ref is an env var name or file path.
CREATE TABLE IF NOT EXISTS company_feishu_app_config (
    id                 BIGINT       NOT NULL,
    app_id             VARCHAR(64)  NOT NULL,
    app_secret_ref     VARCHAR(256) NOT NULL,
    status             VARCHAR(16)  NOT NULL,
    last_verified_at   BIGINT       NULL,
    last_verify_error  VARCHAR(512) NULL,
    created_at         BIGINT       NOT NULL,
    created_by         BIGINT       NULL,
    updated_at         BIGINT       NOT NULL,
    updated_by         BIGINT       NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_company_feishu_app_id (app_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
