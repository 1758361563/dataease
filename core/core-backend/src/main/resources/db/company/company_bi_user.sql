-- Company BI user table. Not a Flyway official migration.
-- 8101 keeps spring.flyway.enabled=false; this file is applied by CompanyBiSchemaInitializer (idempotent).
CREATE TABLE IF NOT EXISTS company_bi_user (
    id              BIGINT       NOT NULL COMMENT 'DataEase userId',
    username        VARCHAR(64)  NOT NULL,
    display_name    VARCHAR(128) NULL,
    password_hash   VARCHAR(128) NOT NULL,
    account_role    VARCHAR(16)  NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    created_at      BIGINT       NOT NULL,
    created_by      BIGINT       NULL,
    updated_at      BIGINT       NOT NULL,
    updated_by      BIGINT       NULL,
    last_login_at   BIGINT       NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_company_bi_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
