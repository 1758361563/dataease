-- One-shot compatibility seed latch. Not a Flyway official migration.
CREATE TABLE IF NOT EXISTS company_bi_compat_seed (
    seed_key        VARCHAR(64) NOT NULL,
    seeded_at       BIGINT      NOT NULL,
    inserted_count  INT         NOT NULL,
    planned_count   INT         NOT NULL,
    PRIMARY KEY (seed_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
