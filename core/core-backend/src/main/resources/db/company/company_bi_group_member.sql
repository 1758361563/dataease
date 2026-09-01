-- Company BI group membership. Idempotent schema only. Not a Flyway official migration.
CREATE TABLE IF NOT EXISTS company_bi_group_member (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    user_id     BIGINT NOT NULL,
    group_id    BIGINT NOT NULL,
    created_at  BIGINT NOT NULL,
    created_by  BIGINT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_company_bi_group_member (user_id, group_id),
    KEY idx_company_bi_group_member_user (user_id),
    KEY idx_company_bi_group_member_group (group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
