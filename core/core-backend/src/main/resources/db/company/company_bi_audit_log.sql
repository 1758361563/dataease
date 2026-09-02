-- Company BI audit log. Idempotent schema only. No FK to Core resource tables.
CREATE TABLE IF NOT EXISTS company_bi_audit_log (
    id                BIGINT        NOT NULL,
    operator_user_id  BIGINT        NOT NULL,
    action_type       VARCHAR(64)   NOT NULL,
    target_type       VARCHAR(32)   NULL,
    target_id         BIGINT        NULL,
    before_json       TEXT          NULL,
    after_json        TEXT          NULL,
    ip                VARCHAR(64)   NULL,
    request_id        VARCHAR(64)   NULL,
    created_at        BIGINT        NOT NULL,
    PRIMARY KEY (id),
    KEY idx_company_bi_audit_operator (operator_user_id),
    KEY idx_company_bi_audit_action (action_type),
    KEY idx_company_bi_audit_target (target_type, target_id),
    KEY idx_company_bi_audit_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
