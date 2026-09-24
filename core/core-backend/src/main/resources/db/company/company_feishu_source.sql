-- Feishu spreadsheet source. App secret and access tokens stay in env, never in this table.
CREATE TABLE IF NOT EXISTS company_feishu_source (
    id                BIGINT       NOT NULL,
    source_name       VARCHAR(128) NOT NULL,
    spreadsheet_url   VARCHAR(512) NOT NULL,
    sheet_id          VARCHAR(64)  NULL,
    sheet_name        VARCHAR(128) NOT NULL,
    header_row        INT          NOT NULL,
    data_start_row    INT          NOT NULL,
    column_range      VARCHAR(32)  NULL COMMENT 'A:M; empty means used columns',
    target_database   VARCHAR(64)  NOT NULL,
    target_table      VARCHAR(128) NOT NULL,
    enabled           TINYINT      NOT NULL DEFAULT 0,
    schedule_json     TEXT         NULL,
    schema_json       TEXT         NOT NULL COMMENT 'accepted fields, name + sql type',
    latest_success_at BIGINT       NULL,
    created_at        BIGINT       NOT NULL,
    created_by        BIGINT       NOT NULL,
    updated_at        BIGINT       NOT NULL,
    updated_by        BIGINT       NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_company_feishu_source_name (source_name),
    KEY idx_company_feishu_source_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
