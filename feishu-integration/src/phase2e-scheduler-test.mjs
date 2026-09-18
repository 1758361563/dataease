import { closePool, nextId, query } from './db.mjs'
import { CONFIG } from './config.mjs'
import { currentDueSlot } from './schedule.mjs'
import { schedulerTick } from './scheduler.mjs'
import { addSubscriptionRecipient, ensureExecutionModeSchema, insertSubscription, persistExecutionMode } from './store.mjs'

function assert(cond, msg) {
  if (!cond) throw new Error(msg)
}

async function main() {
  assert(CONFIG.dryRun === true, 'Phase 2E tests require CONFIG.dryRun=true')
  await ensureExecutionModeSchema()
  const recipients = await query(
    `SELECT id FROM company_feishu_recipient WHERE status='ENABLED' ORDER BY recipient_type LIMIT 2`
  )
  assert(recipients.length >= 1, 'need at least one ENABLED recipient')
  const now = Date.now()
  const hhmm = new Date(now + 8 * 3600 * 1000)
  const time = `${String(hhmm.getUTCHours()).padStart(2, '0')}:${String(hhmm.getUTCMinutes()).padStart(2, '0')}`
  const due = currentDueSlot(now, { kind: 'daily', autoRun: true, time, timezone: 'Asia/Shanghai' })
  assert(due, `expected current slot for ${time}`)

  const subId = nextId()
  await insertSubscription({
    id: subId,
    name: `[2E] scheduler ${subId}`,
    dashboardId: CONFIG.defaultDashboardId,
    chartId: CONFIG.defaultChartId,
    chartName: '2E scheduler',
    scheduleJson: JSON.stringify({
      kind: 'daily',
      autoRun: true,
      time,
      timezone: 'Asia/Shanghai'
    }),
    status: 'ENABLED',
    createdBy: 1
  })
  for (const row of recipients) {
    await addSubscriptionRecipient(subId, row.id, 1)
  }

  const first = await schedulerTick({ now, dryRun: true })
  assert(first.skipped === false, `lock failed: ${first.reason}`)
  const firstHit = first.result.fired.filter(row => String(row.subscriptionId) === String(subId))
  assert(firstHit.length === 1, `expected 1 fire, got ${firstHit.length}`)
  assert(firstHit[0].executionMode === 'DRY_RUN' || firstHit[0].skipped, 'scheduled run must stay dry-run')

  const second = await schedulerTick({ now, dryRun: true })
  const secondHit = second.result.fired.filter(row => String(row.subscriptionId) === String(subId))
  assert(secondHit.length === 1, 'second tick must see the same slot')
  assert(secondHit[0].skipped === true, 'same planned_at must not create another live/send run')

  const runs = await query(
    `SELECT id, trigger_type, planned_at, status, error_summary, execution_mode
       FROM company_feishu_delivery_run
      WHERE subscription_id = ? AND trigger_type = 'SCHEDULED'`,
    [subId]
  )
  assert(runs.length === 1, `expected 1 scheduled run, got ${runs.length}`)
  assert(String(runs[0].planned_at) === String(due), 'planned_at must match Shanghai slot')
  assert(['SUCCEEDED', 'SKIPPED'].includes(runs[0].status), `unexpected status ${runs[0].status}`)
  assert(String(runs[0].execution_mode || '').toUpperCase() === 'DRY_RUN', `expected stored DRY_RUN, got ${runs[0].execution_mode}`)
  const locked = await persistExecutionMode(runs[0].id, 'LIVE')
  assert(locked === 'DRY_RUN', `dry-run run must not upgrade to LIVE, got ${locked}`)
  assert(String(runs[0].error_summary || '').toLowerCase().includes('dry-run') || runs[0].status === 'SKIPPED',
    'scheduled dry-run must keep dry-run evidence')

  const pendingId = nextId()
  await query(
    `INSERT INTO company_feishu_delivery_run
      (id, subscription_id, trigger_type, planned_at, operator_user_id, status, created_at)
     VALUES (?, ?, 'MANUAL', ?, NULL, 'PENDING', ?)`,
    [pendingId, subId, now + 1, now]
  )
  const recovered = await schedulerTick({ now, dryRun: true })
  const recoveredRun = await query(`SELECT status, error_summary FROM company_feishu_delivery_run WHERE id = ?`, [
    pendingId
  ])
  assert(recoveredRun[0].status !== 'PENDING', 'incomplete PENDING run must be recovered')
  assert(recovered.skipped === false)

  const freshId = nextId()
  await insertSubscription({
    id: freshId,
    name: `[2E] freshness ${freshId}`,
    dashboardId: CONFIG.defaultDashboardId,
    chartId: CONFIG.defaultChartId,
    scheduleJson: JSON.stringify({ kind: 'daily', autoRun: true, time, timezone: 'Asia/Shanghai' }),
    freshnessJson: JSON.stringify({ requireEtl: true }),
    status: 'ENABLED',
    createdBy: 1
  })
  await addSubscriptionRecipient(freshId, recipients[0].id, 1)
  const blocked = await schedulerTick({ now, dryRun: true })
  const freshHit = blocked.result.fired.find(row => String(row.subscriptionId) === String(freshId))
  assert(freshHit?.skipped, 'freshness-configured subscription must skip')
  assert(String(freshHit.reason || '').includes('no reliable data source'), freshHit.reason)

  await query(`DELETE FROM company_feishu_delivery_target WHERE run_id IN (SELECT id FROM company_feishu_delivery_run WHERE subscription_id IN (?, ?))`, [
    subId,
    freshId
  ]).catch(async () => {
    const runIds = await query(
      `SELECT id FROM company_feishu_delivery_run WHERE subscription_id IN (?, ?)`,
      [subId, freshId]
    )
    if (runIds.length) {
      const placeholders = runIds.map(() => '?').join(',')
      await query(
        `DELETE FROM company_feishu_delivery_target WHERE run_id IN (${placeholders})`,
        runIds.map(r => r.id)
      )
    }
  })
  await query(`DELETE FROM company_feishu_delivery_run WHERE subscription_id IN (?, ?)`, [subId, freshId])
  await query(`DELETE FROM company_feishu_subscription_recipient WHERE subscription_id IN (?, ?)`, [subId, freshId])
  await query(`DELETE FROM company_feishu_subscription WHERE id IN (?, ?)`, [subId, freshId])

  console.log(`[phase2e-scheduler-test] PASS slot=${due} time=${time} run=${runs[0].id}`)
}

main()
  .catch(err => {
    console.error('[phase2e-scheduler-test] FAIL', err.message)
    process.exitCode = 1
  })
  .finally(() => closePool().catch(() => {}))
