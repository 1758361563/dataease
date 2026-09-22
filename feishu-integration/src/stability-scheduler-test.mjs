import { closePool, nextId, query } from './db.mjs'
import { CONFIG } from './config.mjs'
import { currentDueSlot } from './schedule.mjs'
import { retryPartialFailures, schedulerTick } from './scheduler.mjs'
import { executeSubscription } from './run-subscription.mjs'
import {
  addSubscriptionRecipient,
  ensureDeliveryLaneSchema,
  ensureExecutionModeSchema,
  insertSubscription
} from './store.mjs'

function assert(cond, msg) {
  if (!cond) throw new Error(msg)
}

function readRuns(names) {
  const wanted = new Set(names)
  const records = []
  if (wanted.has('stab-ok')) {
    records.push({
      id: 9,
      etl_name: 'stab-ok',
      status: 'success',
      finished_at: '2026-09-22 03:00:00',
      window_start: '2026-09-21',
      window_end: '2026-09-22'
    })
  }
  if (wanted.has('stab-fail')) {
    records.push(
      {
        id: 1,
        etl_name: 'stab-fail',
        status: 'success',
        finished_at: '2026-09-21 03:00:00',
        window_start: '2026-09-20',
        window_end: '2026-09-21'
      },
      {
        id: 4,
        etl_name: 'stab-fail',
        status: 'fail',
        finished_at: '2026-09-22 03:00:00',
        window_start: '2026-09-21',
        window_end: '2026-09-22'
      }
    )
  }
  if (wanted.has('stab-short')) {
    records.push({
      id: 7,
      etl_name: 'stab-short',
      status: 'success',
      finished_at: '2026-09-22 01:00:00',
      window_start: '2026-09-21',
      window_end: '2026-09-22'
    })
    records.push({
      id: 8,
      etl_name: '全渠道-销售',
      status: 'success',
      finished_at: '2026-09-22 03:00:00',
      window_start: '2026-09-21',
      window_end: '2026-09-23'
    })
  }
  return { available: true, records }
}

async function formalLogCount() {
  try {
    const rows = await query(`SELECT COUNT(*) AS c FROM etl_run_log`)
    return Number(rows[0].c ?? rows[0].C)
  } catch (err) {
    if (/1146|doesn't exist|不存在/i.test(String(err.message || err))) return null
    throw err
  }
}

async function cleanup(ids) {
  if (!ids.length) return
  const marks = ids.map(() => '?').join(',')
  const runIds = await query(
    `SELECT id FROM company_feishu_delivery_run WHERE subscription_id IN (${marks})`,
    ids
  )
  if (runIds.length) {
    const runMarks = runIds.map(() => '?').join(',')
    await query(
      `DELETE FROM company_feishu_delivery_target WHERE run_id IN (${runMarks})`,
      runIds.map(row => row.id)
    )
  }
  await query(`DELETE FROM company_feishu_delivery_run WHERE subscription_id IN (${marks})`, ids)
  await query(`DELETE FROM company_feishu_subscription_recipient WHERE subscription_id IN (${marks})`, ids)
  await query(`DELETE FROM company_feishu_subscription WHERE id IN (${marks})`, ids)
}

async function main() {
  assert(CONFIG.dryRun === true, 'stability scheduler test refuses to run unless the service is in dry-run')
  await ensureExecutionModeSchema()
  await ensureDeliveryLaneSchema()
  const beforeFormal = await formalLogCount()
  const beforeProd = await query(
    `SELECT COUNT(*) AS c FROM company_feishu_subscription WHERE delivery_lane = 'PROD'`
  )
  const recipients = await query(
    `SELECT id FROM company_feishu_recipient WHERE status='ENABLED' ORDER BY recipient_type LIMIT 2`
  )
  assert(recipients.length >= 2, 'need two ENABLED recipients for the isolated retry case')
  const now = Date.now()
  const hhmm = new Date(now + 8 * 3600 * 1000)
  const time = `${String(hhmm.getUTCHours()).padStart(2, '0')}:${String(hhmm.getUTCMinutes()).padStart(2, '0')}`
  const due = currentDueSlot(now, { kind: 'daily', autoRun: true, time, timezone: 'Asia/Shanghai' })
  assert(due, `expected a due slot for ${time}`)
  const scheduleJson = JSON.stringify({ kind: 'daily', autoRun: true, time, timezone: 'Asia/Shanghai' })
  const ids = []

  async function makeSub(name, lane, freshness) {
    const id = nextId()
    ids.push(id)
    await insertSubscription({
      id,
      name,
      dashboardId: CONFIG.defaultDashboardId,
      chartId: CONFIG.defaultChartId,
      chartName: name,
      scheduleJson,
      freshnessJson: freshness ? JSON.stringify(freshness) : null,
      status: 'ENABLED',
      createdBy: 1
    })
    if (lane === 'PROD') {
      await query(`UPDATE company_feishu_subscription SET delivery_lane='PROD' WHERE id=?`, [id])
    }
    for (const row of recipients) await addSubscriptionRecipient(id, row.id, 1)
    return id
  }

  try {
    const unbound = await makeSub(`[stab] unbound ${now}`, 'PROD', null)
    const covered = await makeSub(`[stab] covered ${now}`, 'PROD', {
      etlTasks: ['stab-ok'],
      businessDate: '2026-09-21'
    })
    const failed = await makeSub(`[stab] failed ${now}`, 'PROD', {
      etlTasks: ['stab-fail'],
      businessDate: '2026-09-21'
    })
    const shortWindow = await makeSub(`[stab] short ${now}`, 'PROD', {
      etlTasks: ['stab-short'],
      businessDate: '2026-09-22'
    })
    const testLane = await makeSub(`[stab] test ${now}`, 'TEST', null)

    const first = await schedulerTick({ now, dryRun: true, readRuns })
    assert(first.skipped === false, first.reason || 'scheduler lock was not acquired')
    const hit = id => first.result.fired.find(row => String(row.subscriptionId) === String(id))

    const unboundHit = hit(unbound)
    assert(unboundHit && unboundHit.skipped !== true, 'unlinked prod subscription must run on its fixed slot')
    assert(unboundHit.executionMode === 'DRY_RUN', unboundHit.executionMode)
    assert(unboundHit.dataUpdateLabel === '数据更新状态未经校验', unboundHit.dataUpdateLabel)

    const coveredHit = hit(covered)
    assert(coveredHit && coveredHit.hold !== true && coveredHit.skipped !== true, 'covering ETL success must allow the dry-run')
    assert(coveredHit.executionMode === 'DRY_RUN', 'service dry-run must keep even a prod subscription simulated')

    assert(hit(failed)?.skipped === true, 'latest ETL failure must hold the slot')
    assert(String(hit(failed)?.reason || '').includes('fail'), hit(failed)?.reason)
    assert(hit(shortWindow)?.skipped === true, 'a window that ends on the business date must not count as coverage')
    assert(String(hit(shortWindow)?.reason || '').includes('stab-short'), hit(shortWindow)?.reason)

    const second = await schedulerTick({ now, dryRun: true, readRuns })
    const again = second.result.fired.filter(row =>
      [unbound, covered].map(String).includes(String(row.subscriptionId))
    )
    assert(again.length === 2, 'both completed slots must be seen again')
    assert(again.every(row => row.reason === 'slot-already-terminal'), JSON.stringify(again))
    for (const id of [unbound, covered, failed, shortWindow]) {
      const runs = await query(
        `SELECT id FROM company_feishu_delivery_run WHERE subscription_id=? AND trigger_type='SCHEDULED'`,
        [id]
      )
      assert(runs.length === 1, `slot ${id} created ${runs.length} runs`)
    }

    const liveRunId = nextId()
    await query(
      `INSERT INTO company_feishu_delivery_run
        (id, subscription_id, trigger_type, planned_at, status, execution_mode, created_at)
       VALUES (?, ?, 'MANUAL', ?, 'PENDING', 'LIVE', ?)`,
      [liveRunId, testLane, now + 5, now]
    )
    const refused = await executeSubscription({
      retryRunId: String(liveRunId),
      subscriptionId: String(testLane),
      triggerType: 'MANUAL',
      dryRun: false,
      closeResources: false
    })
    assert(refused.skipped === true, 'a test subscription must not continue a LIVE run')
    assert(String(refused.reason || '').includes('test subscription'), refused.reason)
    const liveTargets = await query(
      `SELECT feishu_message_id FROM company_feishu_delivery_target WHERE run_id=?`,
      [liveRunId]
    )
    assert(liveTargets.every(row => !row.feishu_message_id), 'refused live run must not create a Feishu message id')

    const partialRunId = nextId()
    const keptMessageId = `stab-keep-${partialRunId}`
    await query(
      `INSERT INTO company_feishu_delivery_run
        (id, subscription_id, trigger_type, planned_at, status, execution_mode, error_summary, created_at)
       VALUES (?, ?, 'MANUAL', ?, 'FAILED', 'DRY_RUN', 'partial', ?)`,
      [partialRunId, testLane, now + 6, now]
    )
    await query(
      `INSERT INTO company_feishu_delivery_target
        (id, run_id, recipient_id, receive_id_type, feishu_id, feishu_message_id, status, retry_count, created_at, updated_at)
       VALUES
        (?, ?, ?, 'user_id', 'stab-ok', ?, 'SUCCEEDED', 0, ?, ?),
        (?, ?, ?, 'chat_id', 'stab-bad', NULL, 'FAILED', 0, ?, ?)`,
      [
        nextId(),
        partialRunId,
        recipients[0].id,
        keptMessageId,
        now,
        now,
        nextId(),
        partialRunId,
        recipients[1].id,
        now,
        now
      ]
    )
    const retried = await retryPartialFailures({ dryRun: true })
    assert(retried.some(row => String(row.runId) === String(partialRunId)), 'partial failure must retry the same run')
    const partialTargets = await query(
      `SELECT recipient_id, status, feishu_message_id FROM company_feishu_delivery_target WHERE run_id=?`,
      [partialRunId]
    )
    const kept = partialTargets.find(row => String(row.recipient_id) === String(recipients[0].id))
    assert(String(kept?.feishu_message_id) === keptMessageId, 'an existing message id must stay on retry')

    const afterFormal = await formalLogCount()
    assert(afterFormal === beforeFormal, `formal etl_run_log changed from ${beforeFormal} to ${afterFormal}`)
    console.log(`[stability-scheduler-test] PASS slot=${due}`)
  } finally {
    await cleanup(ids)
    const afterProd = await query(
      `SELECT COUNT(*) AS c FROM company_feishu_subscription WHERE delivery_lane = 'PROD'`
    )
    const beforeCount = Number(beforeProd[0].c ?? beforeProd[0].C)
    const afterCount = Number(afterProd[0].c ?? afterProd[0].C)
    if (beforeCount !== afterCount) {
      throw new Error(`PROD subscription count changed from ${beforeCount} to ${afterCount}`)
    }
  }
}

main()
  .catch(err => {
    console.error('[stability-scheduler-test] FAIL', err.message)
    process.exitCode = 1
  })
  .finally(() => closePool().catch(() => {}))
