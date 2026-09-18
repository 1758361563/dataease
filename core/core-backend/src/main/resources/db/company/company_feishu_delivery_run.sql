-- Delivery run written only by feishu-integration. Screenshot/upload are run-level stages.
-- UNIQUE(subscription_id, trigger_type, planned_at) prevents duplicate scheduled fires;
-- MANUAL uses the trigger millisecond as planned_at so it will not collide.
CREATE TABLE IF NOT EXISTS company_feishu_delivery_run (
    id                  BIGINT       NOT NULL,
    subscription_id     BIGINT       NOT NULL,
    trigger_type        VARCHAR(16)  NOT NULL COMMENT 'MANUAL / SCHEDULED',
    planned_at          BIGINT       NOT NULL,
    operator_user_id    BIGINT       NULL,
    status              VARCHAR(16)  NOT NULL COMMENT 'PENDING / RUNNING / SUCCEEDED / FAILED / SKIPPED / UNKNOWN',
    execution_mode      VARCHAR(16)  NULL COMMENT 'DRY_RUN / LIVE / UNKNOWN; set at run creation, never inferred from screenshot/upload',
    screenshot_status   VARCHAR(16)  NULL,
    screenshot_error    VARCHAR(512) NULL,
    screenshot_at       BIGINT       NULL,
    png_ref             VARCHAR(512) NULL,
    upload_status       VARCHAR(16)  NULL,
    upload_error        VARCHAR(512) NULL,
    upload_at           BIGINT       NULL,
    image_key           VARCHAR(128) NULL,
    error_summary       VARCHAR(512) NULL,
    started_at          BIGINT       NULL,
    finished_at         BIGINT       NULL,
    created_at          BIGINT       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_company_feishu_run_sched (subscription_id, trigger_type, planned_at),
    KEY idx_company_feishu_run_sub (subscription_id, created_at),
    KEY idx_company_feishu_run_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
