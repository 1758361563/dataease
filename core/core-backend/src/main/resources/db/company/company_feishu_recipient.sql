-- Feishu recipients. receive_id_type is explicit (user_id / chat_id); do not infer from prefix.
-- feishu_id for PERSONAL is user_id (观远「飞书账号」); for CHAT is chat_id.
-- No FK to DataEase core tables. identity_status is search-confirm only.
-- verify_status stays UNVERIFIED until a real send succeeds.
CREATE TABLE IF NOT EXISTS company_feishu_recipient (
    id              BIGINT       NOT NULL,
    recipient_type  VARCHAR(16)  NOT NULL COMMENT 'PERSONAL / CHAT',
    receive_id_type VARCHAR(16)  NOT NULL COMMENT 'user_id / chat_id',
    display_name    VARCHAR(128) NOT NULL,
    feishu_id       VARCHAR(64)  NOT NULL,
    union_id        VARCHAR(64)  NULL,
    app_id          VARCHAR(64)  NOT NULL,
    source          VARCHAR(32)  NULL COMMENT 'MANUAL / CONTACT_LOOKUP / GUANDATA_FEISHU_ACCOUNT',
    identity_status VARCHAR(16)  NOT NULL DEFAULT 'UNCONFIRMED' COMMENT 'UNCONFIRMED / CONFIRMED. Not send-verified',
    verify_status   VARCHAR(16)  NOT NULL COMMENT 'UNVERIFIED / VERIFIED / FAILED',
    status          VARCHAR(16)  NOT NULL COMMENT 'ENABLED / DISABLED',
    last_verified_at BIGINT      NULL,
    created_at      BIGINT       NOT NULL,
    created_by      BIGINT       NULL,
    updated_at      BIGINT       NOT NULL,
    updated_by      BIGINT       NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_company_feishu_recipient (app_id, receive_id_type, feishu_id),
    KEY idx_company_feishu_recipient_status (status, verify_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
