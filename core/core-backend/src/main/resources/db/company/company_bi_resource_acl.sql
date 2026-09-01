-- Company BI resource ACL. Idempotent schema only. No FK to DataEase core tables.
CREATE TABLE IF NOT EXISTS company_bi_resource_acl (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    subject_type    VARCHAR(16) NOT NULL,
    subject_id      BIGINT      NOT NULL,
    resource_type   VARCHAR(32) NOT NULL,
    resource_id     BIGINT      NOT NULL,
    relation_level  VARCHAR(16) NOT NULL,
    created_at      BIGINT      NOT NULL,
    created_by      BIGINT      NULL,
    updated_at      BIGINT      NOT NULL,
    updated_by      BIGINT      NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_company_bi_resource_acl (subject_type, subject_id, resource_type, resource_id),
    KEY idx_company_bi_acl_subject (subject_type, subject_id),
    KEY idx_company_bi_acl_resource (resource_type, resource_id),
    KEY idx_company_bi_acl_subject_resource (subject_type, subject_id, resource_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
