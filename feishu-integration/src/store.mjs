import { exec, nextId, query } from './db.mjs'

const now = () => Date.now()

export async function insertAppConfig(row) {
  const id = row.id ?? nextId()
  const ts = now()
  await exec(
    `INSERT INTO company_feishu_app_config
      (id, app_id, app_secret_ref, status, last_verified_at, last_verify_error, created_at, created_by, updated_at, updated_by)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    [
      id,
      row.appId,
      row.appSecretRef,
      row.status || 'ENABLED',
      row.lastVerifiedAt ?? null,
      row.lastVerifyError ?? null,
      ts,
      row.createdBy ?? null,
      ts,
      row.updatedBy ?? null
    ]
  )
  return id
}

export async function insertRecipient(row) {
  const id = row.id ?? nextId()
  const ts = now()
  await exec(
    `INSERT INTO company_feishu_recipient
      (id, recipient_type, receive_id_type, display_name, feishu_id, union_id, app_id, source, identity_status,
       verify_status, status, last_verified_at, created_at, created_by, updated_at, updated_by)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    [
      id,
      row.recipientType,
      row.receiveIdType,
      row.displayName,
      row.feishuId,
      row.unionId ?? null,
      row.appId,
      row.source ?? 'MANUAL',
      row.identityStatus || 'UNCONFIRMED',
      row.verifyStatus || 'UNVERIFIED',
      row.status || 'ENABLED',
      row.lastVerifiedAt ?? null,
      ts,
      row.createdBy ?? null,
      ts,
      row.updatedBy ?? null
    ]
  )
  return id
}

export async function insertIdentityMap(row) {
  const id = row.id ?? nextId()
  const ts = now()
  await exec(
    `INSERT INTO company_feishu_identity_map
      (id, bi_user_id, recipient_id, app_id, source, status, created_at, created_by, updated_at, updated_by)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    [
      id,
      row.biUserId,
      row.recipientId,
      row.appId,
      row.source ?? 'MANUAL',
      row.status || 'ENABLED',
      ts,
      row.createdBy ?? null,
      ts,
      row.updatedBy ?? null
    ]
  )
  return id
}

export async function insertSubscription(row) {
  const id = row.id ?? nextId()
  const ts = now()
  await exec(
    `INSERT INTO company_feishu_subscription
      (id, name, dashboard_id, chart_id, chart_name, title_template, body_template, include_detail_link,
       fixed_filter_json, biz_date_rule, schedule_json, freshness_json, status, created_at, created_by, updated_at, updated_by)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    [
      id,
      row.name,
      row.dashboardId,
      row.chartId,
      row.chartName ?? null,
      row.titleTemplate ?? null,
      row.bodyTemplate ?? null,
      row.includeDetailLink ? 1 : 0,
      row.fixedFilterJson ?? null,
      row.bizDateRule ?? null,
      row.scheduleJson ?? null,
      row.freshnessJson ?? null,
      row.status || 'DRAFT',
      ts,
      row.createdBy,
      ts,
      row.updatedBy ?? row.createdBy
    ]
  )
  return id
}

export async function addSubscriptionRecipient(subscriptionId, recipientId, createdBy = null) {
  const id = nextId()
  await exec(
    `INSERT INTO company_feishu_subscription_recipient
      (id, subscription_id, recipient_id, created_at, created_by)
     VALUES (?, ?, ?, ?, ?)`,
    [id, subscriptionId, recipientId, now(), createdBy]
  )
  return id
}

export async function listSubscriptionRecipients(subscriptionId) {
  return query(
    `SELECT r.*
       FROM company_feishu_subscription_recipient sr
       JOIN company_feishu_recipient r ON r.id = sr.recipient_id
      WHERE sr.subscription_id = ?
      ORDER BY r.recipient_type, r.id`,
    [subscriptionId]
  )
}

export async function ensureIdentityStatusSchema() {
  const cols = await query(
    `SELECT COLUMN_NAME AS column_name
       FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'company_feishu_recipient'
        AND COLUMN_NAME = 'identity_status'`
  )
  const present = cols.some(row => String(row.column_name || row.COLUMN_NAME || '').toLowerCase() === 'identity_status')
  if (!present) {
    await exec(
      `ALTER TABLE company_feishu_recipient
         ADD COLUMN identity_status VARCHAR(16) NOT NULL DEFAULT 'UNCONFIRMED'
         COMMENT 'UNCONFIRMED / CONFIRMED. Search-confirm only; not send-verified' AFTER source`
    )
  }
}

export async function ensureExecutionModeSchema() {
  const cols = await query(
    `SELECT COLUMN_NAME AS column_name
       FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'company_feishu_delivery_run'
        AND COLUMN_NAME = 'execution_mode'`
  )
  const present = cols.some(row => String(row.column_name || row.COLUMN_NAME || '').toLowerCase() === 'execution_mode')
  if (!present) {
    await exec(
      `ALTER TABLE company_feishu_delivery_run
         ADD COLUMN execution_mode VARCHAR(16) NULL COMMENT 'DRY_RUN / LIVE / UNKNOWN' AFTER status`
    )
  }
  await exec(
    `UPDATE company_feishu_delivery_run r
        SET execution_mode = 'DRY_RUN'
      WHERE execution_mode IS NULL
        AND (
          LOWER(IFNULL(error_summary, '')) LIKE '%dry-run%'
          OR EXISTS (
            SELECT 1 FROM company_feishu_delivery_target t
             WHERE t.run_id = r.id AND LOWER(IFNULL(t.error_summary, '')) LIKE '%dry-run%'
          )
        )`
  )
  await exec(
    `UPDATE company_feishu_delivery_run r
        SET execution_mode = 'LIVE'
      WHERE execution_mode IS NULL
        AND EXISTS (
          SELECT 1 FROM company_feishu_delivery_target t
           WHERE t.run_id = r.id
             AND t.feishu_message_id IS NOT NULL
             AND t.feishu_message_id <> ''
        )`
  )
  await exec(
    `UPDATE company_feishu_delivery_run
        SET execution_mode = 'UNKNOWN'
      WHERE execution_mode IS NULL`
  )
}

export async function persistExecutionMode(runId, mode) {
  const { normalizeExecutionMode, canUpgradeExecutionMode } = await import('./execution-mode.mjs')
  const wanted = normalizeExecutionMode(mode)
  if (!wanted || wanted === 'UNKNOWN') return null
  const run = await getDeliveryRunById(runId)
  if (!run) return null
  const current = normalizeExecutionMode(run.execution_mode)
  if (current === 'DRY_RUN' || current === 'LIVE') {
    if (!canUpgradeExecutionMode(current, wanted)) return current
    return current
  }
  await updateDeliveryRun(runId, { executionMode: wanted })
  return wanted
}

export async function insertDeliveryRun(row) {
  const id = row.id ?? nextId()
  const ts = now()
  await exec(
    `INSERT INTO company_feishu_delivery_run
      (id, subscription_id, trigger_type, planned_at, operator_user_id, status, execution_mode,
       screenshot_status, screenshot_error, screenshot_at, png_ref,
       upload_status, upload_error, upload_at, image_key,
       error_summary, started_at, finished_at, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    [
      id,
      row.subscriptionId,
      row.triggerType,
      row.plannedAt,
      row.operatorUserId ?? null,
      row.status || 'PENDING',
      row.executionMode ?? null,
      row.screenshotStatus ?? null,
      row.screenshotError ?? null,
      row.screenshotAt ?? null,
      row.pngRef ?? null,
      row.uploadStatus ?? null,
      row.uploadError ?? null,
      row.uploadAt ?? null,
      row.imageKey ?? null,
      row.errorSummary ?? null,
      row.startedAt ?? null,
      row.finishedAt ?? null,
      ts
    ]
  )
  return id
}

export async function updateDeliveryRun(id, patch) {
  const fields = []
  const values = []
  for (const [key, col] of Object.entries({
    status: 'status',
    screenshotStatus: 'screenshot_status',
    screenshotError: 'screenshot_error',
    screenshotAt: 'screenshot_at',
    pngRef: 'png_ref',
    uploadStatus: 'upload_status',
    uploadError: 'upload_error',
    uploadAt: 'upload_at',
    imageKey: 'image_key',
    errorSummary: 'error_summary',
    executionMode: 'execution_mode',
    startedAt: 'started_at',
    finishedAt: 'finished_at'
  })) {
    if (patch[key] !== undefined) {
      fields.push(`${col} = ?`)
      values.push(patch[key])
    }
  }
  if (!fields.length) return
  values.push(id)
  await exec(`UPDATE company_feishu_delivery_run SET ${fields.join(', ')} WHERE id = ?`, values)
}

export async function insertDeliveryTarget(row) {
  const id = row.id ?? nextId()
  const ts = now()
  await exec(
    `INSERT INTO company_feishu_delivery_target
      (id, run_id, recipient_id, receive_id_type, feishu_id, feishu_message_id, status, retry_count, error_summary, sent_at, created_at, updated_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    [
      id,
      row.runId,
      row.recipientId,
      row.receiveIdType,
      row.feishuId,
      row.feishuMessageId ?? null,
      row.status || 'PENDING',
      row.retryCount ?? 0,
      row.errorSummary ?? null,
      row.sentAt ?? null,
      ts,
      ts
    ]
  )
  return id
}

export async function listFailedTargets(runId) {
  return query(
    `SELECT t.*, r.display_name
       FROM company_feishu_delivery_target t
       JOIN company_feishu_recipient r ON r.id = t.recipient_id
      WHERE t.run_id = ? AND t.status IN ('FAILED', 'UNKNOWN')
      ORDER BY t.id`,
    [runId]
  )
}

export async function getAppConfigByAppId(appId) {
  const rows = await query(`SELECT * FROM company_feishu_app_config WHERE app_id = ?`, [appId])
  return rows[0] || null
}

export async function getSubscriptionById(id) {
  const rows = await query(`SELECT * FROM company_feishu_subscription WHERE id = ?`, [id])
  return rows[0] || null
}

export async function findSubscriptionByName(name) {
  const rows = await query(
    `SELECT * FROM company_feishu_subscription WHERE name = ? ORDER BY id DESC`,
    [name]
  )
  return rows[0] || null
}

export async function findRecipient(appId, receiveIdType, feishuId) {
  const rows = await query(
    `SELECT * FROM company_feishu_recipient WHERE app_id = ? AND receive_id_type = ? AND feishu_id = ?`,
    [appId, receiveIdType, feishuId]
  )
  return rows[0] || null
}

export async function getIdentityMapByRecipient(recipientId) {
  const rows = await query(`SELECT * FROM company_feishu_identity_map WHERE recipient_id = ?`, [
    recipientId
  ])
  return rows[0] || null
}

export async function getDeliveryRunById(id) {
  const rows = await query(`SELECT * FROM company_feishu_delivery_run WHERE id = ?`, [id])
  return rows[0] || null
}

export async function findDeliveryRun(subscriptionId, triggerType, plannedAt) {
  const rows = await query(
    `SELECT * FROM company_feishu_delivery_run
      WHERE subscription_id = ? AND trigger_type = ? AND planned_at = ?`,
    [subscriptionId, triggerType, plannedAt]
  )
  return rows[0] || null
}

export async function listDeliveryTargets(runId) {
  return query(
    `SELECT t.*, r.display_name, r.recipient_type
       FROM company_feishu_delivery_target t
       JOIN company_feishu_recipient r ON r.id = t.recipient_id
      WHERE t.run_id = ?
      ORDER BY t.id`,
    [runId]
  )
}

export async function updateDeliveryTarget(id, patch) {
  const fields = []
  const values = []
  for (const [key, col] of Object.entries({
    feishuMessageId: 'feishu_message_id',
    status: 'status',
    retryCount: 'retry_count',
    errorSummary: 'error_summary',
    sentAt: 'sent_at'
  })) {
    if (patch[key] !== undefined) {
      fields.push(`${col} = ?`)
      values.push(patch[key])
    }
  }
  if (!fields.length) return
  fields.push('updated_at = ?')
  values.push(now())
  values.push(id)
  await exec(`UPDATE company_feishu_delivery_target SET ${fields.join(', ')} WHERE id = ?`, values)
}

export async function listEnabledSubscriptions() {
  return query(`SELECT * FROM company_feishu_subscription WHERE status = 'ENABLED' ORDER BY id`)
}

export async function listIncompleteDeliveryRuns(limit = 50) {
  return query(
    `SELECT * FROM company_feishu_delivery_run
      WHERE status IN ('PENDING', 'RUNNING')
      ORDER BY created_at
      LIMIT ?`,
    [limit]
  )
}

export async function listPartialRetryRuns(maxRetries = 3, limit = 20) {
  return query(
    `SELECT r.*
       FROM company_feishu_delivery_run r
      WHERE r.status = 'FAILED'
        AND (
          r.execution_mode IS NULL
          OR r.execution_mode <> 'LIVE'
          OR EXISTS (
            SELECT 1 FROM company_feishu_subscription s
             WHERE s.id = r.subscription_id AND s.delivery_lane = 'PROD'
          )
        )
        AND EXISTS (
          SELECT 1 FROM company_feishu_delivery_target t
           WHERE t.run_id = r.id
             AND t.status = 'SUCCEEDED'
             AND (
               r.execution_mode = 'DRY_RUN'
               OR (t.feishu_message_id IS NOT NULL AND t.feishu_message_id <> '')
             )
        )
        AND EXISTS (
          SELECT 1 FROM company_feishu_delivery_target t
           WHERE t.run_id = r.id
             AND t.status IN ('FAILED', 'UNKNOWN')
             AND IFNULL(t.retry_count, 0) < ?
        )
      ORDER BY r.finished_at
      LIMIT ?`,
    [maxRetries, limit]
  )
}

export async function ensureDeliveryLaneSchema() {
  const cols = await query(
    `SELECT COLUMN_NAME AS column_name
       FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'company_feishu_subscription'
        AND COLUMN_NAME = 'delivery_lane'`
  )
  const present = cols.some(row => String(row.column_name || row.COLUMN_NAME || '').toLowerCase() === 'delivery_lane')
  if (!present) {
    await exec(
      `ALTER TABLE company_feishu_subscription
         ADD COLUMN delivery_lane VARCHAR(16) NOT NULL DEFAULT 'TEST'
         COMMENT 'TEST subscriptions never live-send. PROD may live-send only when the service dry-run flag is off.'
         AFTER status`
    )
  }
}

export async function withSchedulerLock(fn) {
  const got = await query(`SELECT GET_LOCK(?, 10) AS locked`, ['company_feishu_scheduler'])
  const locked = Number(got?.[0]?.locked)
  if (locked !== 1) return { skipped: true, reason: 'lock-not-acquired' }
  try {
    const result = await fn()
    return { skipped: false, result }
  } finally {
    await query(`SELECT RELEASE_LOCK(?) AS released`, ['company_feishu_scheduler'])
  }
}

export async function claimDeliveryRun({ subscriptionId, triggerType, plannedAt, operatorUserId, executionMode }) {
  const existing = await findDeliveryRun(subscriptionId, triggerType, plannedAt)
  if (existing) {
    if (executionMode) await persistExecutionMode(existing.id, executionMode)
    return { run: await getDeliveryRunById(existing.id), created: false }
  }
  try {
    const id = await insertDeliveryRun({
      subscriptionId,
      triggerType,
      plannedAt,
      operatorUserId,
      status: 'PENDING',
      executionMode: executionMode || null
    })
    return { run: await getDeliveryRunById(id), created: true }
  } catch (err) {
    if (err.code === 'ER_DUP_ENTRY' || err.errno === 1062) {
      const raced = await findDeliveryRun(subscriptionId, triggerType, plannedAt)
      if (raced) {
        if (executionMode) await persistExecutionMode(raced.id, executionMode)
        return { run: await getDeliveryRunById(raced.id), created: false }
      }
    }
    throw err
  }
}

export async function deleteTestRows(ids) {
  if (!ids.length) return
  const placeholders = ids.map(() => '?').join(',')
  await exec(`DELETE FROM company_feishu_delivery_target WHERE run_id IN (${placeholders}) OR id IN (${placeholders})`, [
    ...ids,
    ...ids
  ])
  await exec(`DELETE FROM company_feishu_delivery_run WHERE id IN (${placeholders}) OR subscription_id IN (${placeholders})`, [
    ...ids,
    ...ids
  ])
  await exec(`DELETE FROM company_feishu_subscription_recipient WHERE subscription_id IN (${placeholders}) OR recipient_id IN (${placeholders})`, [
    ...ids,
    ...ids
  ])
  await exec(`DELETE FROM company_feishu_identity_map WHERE id IN (${placeholders}) OR recipient_id IN (${placeholders})`, [
    ...ids,
    ...ids
  ])
  await exec(`DELETE FROM company_feishu_subscription WHERE id IN (${placeholders})`, ids)
  await exec(`DELETE FROM company_feishu_recipient WHERE id IN (${placeholders})`, ids)
  await exec(`DELETE FROM company_feishu_app_config WHERE id IN (${placeholders})`, ids)
}
