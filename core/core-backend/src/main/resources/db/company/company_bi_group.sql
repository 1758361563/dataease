-- Company BI group table. Idempotent schema only. Not a Flyway official migration.
CREATE TABLE IF NOT EXISTS company_bi_group (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    name         VARCHAR(128) NOT NULL,
    description  VARCHAR(512) NULL,
    status       VARCHAR(16)  NOT NULL,
    created_at   BIGINT       NOT NULL,
    created_by   BIGINT       NULL,
    updated_at   BIGINT       NOT NULL,
    updated_by   BIGINT       NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_company_bi_group_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
