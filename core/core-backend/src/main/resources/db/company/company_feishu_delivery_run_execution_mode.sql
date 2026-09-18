-- Additive compatibility migration for existing DBs.
-- CREATE TABLE IF NOT EXISTS will not add columns to live company_feishu_delivery_run tables.
-- Applied idempotently by CompanyBiSchemaInitializer and feishu-integration ensureExecutionModeSchema().
-- Historical backfill:
--   dry-run evidence -> DRY_RUN
--   feishu_message_id present -> LIVE
--   otherwise UNKNOWN
-- Do not infer LIVE from screenshot_status / upload_status.

ALTER TABLE company_feishu_delivery_run
    ADD COLUMN execution_mode VARCHAR(16) NULL COMMENT 'DRY_RUN / LIVE / UNKNOWN' AFTER status;
