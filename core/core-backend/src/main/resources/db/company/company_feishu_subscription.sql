-- One subscription = one card + one fixed filter set + one schedule + N recipients.
-- dashboard_id / chart_id are VARCHAR because frontend IDs exceed JS safe integers.
CREATE TABLE IF NOT EXISTS company_feishu_subscription (
    id                   BIGINT       NOT NULL,
    name                 VARCHAR(128) NOT NULL,
    dashboard_id         VARCHAR(64)  NOT NULL,
    chart_id             VARCHAR(64)  NOT NULL,
    chart_name           VARCHAR(256) NULL,
    title_template       VARCHAR(256) NULL,
    body_template        TEXT         NULL,
    include_detail_link  TINYINT      NOT NULL DEFAULT 0,
    fixed_filter_json    TEXT         NULL,
    biz_date_rule        VARCHAR(64)  NULL,
    schedule_json        TEXT         NULL COMMENT 'manual/daily/weekly/monthly + HH:mm + Asia/Shanghai',
    freshness_json       TEXT         NULL,
    status               VARCHAR(16)  NOT NULL COMMENT 'DRAFT / ENABLED / DISABLED / DELETED',
    delivery_lane        VARCHAR(16)  NOT NULL DEFAULT 'TEST' COMMENT 'TEST never live-sends. PROD may live-send only when service dry-run is off.',
    created_at           BIGINT       NOT NULL,
    created_by           BIGINT       NOT NULL,
    updated_at           BIGINT       NOT NULL,
    updated_by           BIGINT       NULL,
    PRIMARY KEY (id),
    KEY idx_company_feishu_sub_card (dashboard_id, chart_id),
    KEY idx_company_feishu_sub_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
