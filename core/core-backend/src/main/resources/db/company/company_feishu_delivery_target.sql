-- Per-recipient send result for a run. SUCCEEDED targets must not be retried.
CREATE TABLE IF NOT EXISTS company_feishu_delivery_target (
    id                 BIGINT       NOT NULL,
    run_id             BIGINT       NOT NULL,
    recipient_id       BIGINT       NOT NULL,
    receive_id_type    VARCHAR(16)  NOT NULL,
    feishu_id          VARCHAR(64)  NOT NULL,
    feishu_message_id  VARCHAR(64)  NULL,
    status             VARCHAR(16)  NOT NULL COMMENT 'PENDING / RUNNING / SUCCEEDED / FAILED / UNKNOWN',
    retry_count        INT          NOT NULL DEFAULT 0,
    error_summary      VARCHAR(512) NULL,
    sent_at            BIGINT       NULL,
    created_at         BIGINT       NOT NULL,
    updated_at         BIGINT       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_company_feishu_target (run_id, recipient_id),
    KEY idx_company_feishu_target_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
