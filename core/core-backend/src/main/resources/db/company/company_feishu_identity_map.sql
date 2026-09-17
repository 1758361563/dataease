-- Optional BI user <-> Feishu personal recipient map. No FK; do not assume 观远 fields are verified.
CREATE TABLE IF NOT EXISTS company_feishu_identity_map (
    id            BIGINT      NOT NULL,
    bi_user_id    BIGINT      NOT NULL,
    recipient_id  BIGINT      NOT NULL,
    app_id        VARCHAR(64) NOT NULL,
    source        VARCHAR(32) NULL COMMENT 'MANUAL / GUANDATA_FEISHU_ACCOUNT',
    status        VARCHAR(16) NOT NULL COMMENT 'ENABLED / INVALID',
    created_at    BIGINT      NOT NULL,
    created_by    BIGINT      NULL,
    updated_at    BIGINT      NOT NULL,
    updated_by    BIGINT      NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_company_feishu_map_user (app_id, bi_user_id),
    UNIQUE KEY uk_company_feishu_map_recipient (app_id, recipient_id),
    KEY idx_company_feishu_map_recipient (recipient_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
