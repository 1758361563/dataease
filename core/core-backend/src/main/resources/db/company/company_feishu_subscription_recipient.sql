-- Subscription <-> recipient many-to-many. No FK to keep company schema style.
CREATE TABLE IF NOT EXISTS company_feishu_subscription_recipient (
    id               BIGINT NOT NULL,
    subscription_id  BIGINT NOT NULL,
    recipient_id     BIGINT NOT NULL,
    created_at       BIGINT NOT NULL,
    created_by       BIGINT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_company_feishu_sub_recipient (subscription_id, recipient_id),
    KEY idx_company_feishu_sub_recipient_rev (recipient_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
