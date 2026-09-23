import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  claimModeForSubscription,
  isConfirmedDelivery,
  isPartialRetryCandidate,
  planScheduledSend,
  resolveSchedulerDryRun,
  subscriptionLane
} from './delivery-lane.mjs'
import {
  ETL_HOLD_PREFIX,
  evaluateFreshness,
  isEtlHoldSummary,
  isMissingRunLog,
  parseFreshnessBinding,
  readEtlRunLog,
  UNCHECKED_LABEL
} from './etl-status.mjs'
import { redactSecrets } from './service-log.mjs'

describe('delivery lane stays dry when send mode flips', () => {
  it('keeps the service in dry-run unless config itself is live', () => {
    assert.equal(resolveSchedulerDryRun({ dryRun: false }, true), true)
    assert.equal(resolveSchedulerDryRun({}, true), true)
    assert.equal(resolveSchedulerDryRun({ dryRun: true }, false), true)
    assert.equal(resolveSchedulerDryRun({ dryRun: false }, false), false)
  })

  it('refuses live send for test subscriptions and allows it only for prod', () => {
    assert.equal(subscriptionLane({}), 'TEST')
    assert.equal(subscriptionLane({ delivery_lane: 'PROD' }), 'PROD')
    const testLane = claimModeForSubscription({ globalDryRun: false, lane: 'TEST' })
    assert.equal(testLane.executionMode, 'DRY_RUN')
    assert.equal(testLane.liveRefused, true)
    const prodLane = claimModeForSubscription({ globalDryRun: false, lane: 'PROD' })
    assert.equal(prodLane.executionMode, 'LIVE')
    assert.equal(prodLane.liveRefused, false)
    const prodWhileDry = claimModeForSubscription({ globalDryRun: true, lane: 'PROD' })
    assert.equal(prodWhileDry.executionMode, 'DRY_RUN')
    assert.equal(prodWhileDry.liveRefused, false)
  })

  it('lets an unlinked prod subscription send on the fixed schedule without claiming the data was updated', () => {
    const freshness = evaluateFreshness(parseFreshnessBinding({}), { available: false, records: [] })
    const live = planScheduledSend({ lane: 'PROD', globalDryRun: false, freshness })
    assert.equal(live.hold, false)
    assert.equal(live.executionMode, 'LIVE')
    assert.equal(live.dataUpdateLabel, UNCHECKED_LABEL)
    const simulated = planScheduledSend({ lane: 'PROD', globalDryRun: true, freshness })
    assert.equal(simulated.executionMode, 'DRY_RUN')
    assert.equal(simulated.hold, false)
    assert.equal(subscriptionLane({}), 'TEST')
  })
})

describe('confirmed recipients are not eligible for another send', () => {
  it('treats a live target as confirmed only with a message id', () => {
    assert.equal(isConfirmedDelivery({ status: 'SUCCEEDED', feishu_message_id: 'om_1' }, 'LIVE'), true)
    assert.equal(isConfirmedDelivery({ status: 'SUCCEEDED', feishu_message_id: null }, 'LIVE'), false)
    assert.equal(isConfirmedDelivery({ status: 'FAILED', feishu_message_id: null }, 'LIVE'), false)
    assert.equal(isConfirmedDelivery({ status: 'SUCCEEDED', feishu_message_id: null }, 'DRY_RUN'), true)
  })

  it('retries only failed targets beside an already confirmed recipient', () => {
    const run = { status: 'FAILED', execution_mode: 'LIVE' }
    const targets = [
      { status: 'SUCCEEDED', feishu_message_id: 'om_ok', retry_count: 0 },
      { status: 'FAILED', feishu_message_id: null, retry_count: 1 }
    ]
    assert.equal(isPartialRetryCandidate(run, targets, 3), true)
    assert.equal(
      isPartialRetryCandidate(run, [{ status: 'FAILED', retry_count: 0 }], 3),
      false
    )
    assert.equal(
      isPartialRetryCandidate(
        run,
        [
          { status: 'SUCCEEDED', feishu_message_id: 'om_ok', retry_count: 0 },
          { status: 'FAILED', retry_count: 3 }
        ],
        3
      ),
      false
    )
    assert.equal(isPartialRetryCandidate({ status: 'SUCCEEDED', execution_mode: 'LIVE' }, targets, 3), false)
  })
})

describe('ETL status is read, never invented', () => {
  it('leaves unbound subscriptions unchecked and unblocked', () => {
    const decision = evaluateFreshness(parseFreshnessBinding({ requireEtl: true }), {
      available: false,
      records: []
    })
    assert.equal(decision.checked, false)
    assert.equal(decision.blocked, false)
    assert.equal(decision.label, UNCHECKED_LABEL)
  })

  it('blocks a linked task when the formal log is missing or the latest run failed', () => {
    const binding = parseFreshnessBinding({
      etlTasks: ['销售订单数据'],
      businessDate: '2026-09-21'
    })
    const missingLog = evaluateFreshness(binding, { available: false, records: [] })
    assert.equal(missingLog.blocked, true)
    assert.match(missingLog.reason, /已阻止发送旧图/)
    const failed = evaluateFreshness(binding, {
      available: true,
      records: [
        {
          etl_name: '销售订单数据',
          status: 'success',
          finished_at: '2026-09-20 02:00:00',
          window_start: '2026-01-01',
          window_end: '2026-09-21'
        },
        {
          etl_name: '销售订单数据',
          status: 'fail',
          finished_at: '2026-09-21 02:00:00',
          window_start: '2026-09-20',
          window_end: '2026-09-21'
        }
      ]
    })
    assert.equal(failed.blocked, true)
    assert.match(failed.reason, /fail/)
    assert.equal(failed.lastSuccessAt, null)
  })

  it('allows send only when the latest linked run succeeded and covers the business date', () => {
    const binding = parseFreshnessBinding({
      etlTasks: ['销售订单数据'],
      businessDate: '2026-09-21'
    })
    const shortWindow = evaluateFreshness(binding, {
      available: true,
      records: [
        {
          etl_name: '销售订单数据',
          status: 'success',
          finished_at: '2026-09-21 03:00:00',
          window_start: '2026-09-21',
          window_end: '2026-09-21'
        }
      ]
    })
    assert.equal(shortWindow.blocked, true)
    const covered = evaluateFreshness(binding, {
      available: true,
      records: [
        {
          etl_name: '销售订单数据',
          status: 'success',
          finished_at: '2026-09-22 03:10:00',
          window_start: '2026-09-21',
          window_end: '2026-09-22'
        }
      ]
    })
    assert.equal(covered.blocked, false)
    assert.equal(covered.checked, true)
    assert.equal(covered.lastSuccessAt, '2026-09-22 03:10:00')
    assert.equal(covered.businessWindow[0].windowEnd, '2026-09-22')
  })

  it('does not use another task, an older success, or a short window', () => {
    const businessDate = '2026-09-22'
    const other = evaluateFreshness(
      parseFreshnessBinding({ etlTasks: ['销售订单数据'], businessDate }),
      {
        available: true,
        records: [
          {
            id: 8,
            etl_name: '全渠道-销售',
            status: 'success',
            finished_at: '2026-09-22 03:00:00',
            window_start: '2026-09-21',
            window_end: '2026-09-23'
          }
        ]
      }
    )
    assert.equal(other.blocked, true)
    assert.match(other.reason, /销售订单数据/)
    const stale = evaluateFreshness(parseFreshnessBinding({ etlTasks: ['销售订单数据'], businessDate }), {
      available: true,
      records: [
        {
          id: 2,
          etl_name: '销售订单数据',
          status: 'success',
          finished_at: '2026-09-21 03:00:00',
          window_start: '2026-09-21',
          window_end: '2026-09-22'
        },
        {
          id: 9,
          etl_name: '销售订单数据',
          status: 'fail',
          finished_at: '2026-09-22 03:00:00',
          window_start: '2026-09-21',
          window_end: '2026-09-22'
        }
      ]
    })
    assert.equal(stale.blocked, true)
    assert.match(stale.reason, /fail/)
    const yesterdayOnly = evaluateFreshness(
      parseFreshnessBinding({ etlTasks: ['销售订单数据'], businessDate }),
      {
        available: true,
        records: [
          {
            id: 4,
            etl_name: '销售订单数据',
            status: 'success',
            finished_at: '2026-09-22 01:00:00',
            window_start: '2026-09-21',
            window_end: '2026-09-22'
          }
        ]
      }
    )
    assert.equal(yesterdayOnly.blocked, true)
    const both = evaluateFreshness(
      parseFreshnessBinding({ etlTasks: ['销售订单数据', '全渠道-销售'], businessDate }),
      {
        available: true,
        records: [
          {
            id: 5,
            etl_name: '销售订单数据',
            status: 'success',
            finished_at: '2026-09-22 03:00:00',
            window_start: '2026-09-22',
            window_end: '2026-09-23'
          }
        ]
      }
    )
    assert.equal(both.blocked, true)
    assert.match(both.reason, /全渠道-销售/)
    const statusOnly = evaluateFreshness(
      parseFreshnessBinding({ etlTasks: ['销售订单数据'] }),
      {
        available: true,
        records: [
          {
            id: 6,
            etl_name: '销售订单数据',
            status: 'success',
            finished_at: '2026-09-22 03:00:00',
            window_start: '2026-01-01',
            window_end: '2026-09-23'
          }
        ]
      }
    )
    assert.equal(statusOnly.blocked, true)
    assert.match(statusOnly.reason, /businessDate/)
  })

  it('reads a schema-qualified run log and rejects unsafe table names', async () => {
    const previous = process.env.ETL_RUN_LOG_TABLE
    const seen = []
    const query = async (sql) => {
      seen.push(sql)
      return []
    }
    try {
      process.env.ETL_RUN_LOG_TABLE = 'qiwei_new_bi.etl_run_log'
      await readEtlRunLog(['销售订单数据'], query)
      process.env.ETL_RUN_LOG_TABLE = 'qiwei_new_bi.etl_run_log; DROP TABLE x'
      await readEtlRunLog(['销售订单数据'], query)
    } finally {
      if (previous == null) delete process.env.ETL_RUN_LOG_TABLE
      else process.env.ETL_RUN_LOG_TABLE = previous
    }
    assert.match(seen[0], /FROM qiwei_new_bi\.etl_run_log/)
    assert.match(seen[1], /FROM etl_run_log\b/)
    assert.equal(seen[1].includes('DROP'), false)
    delete process.env.ETL_RUN_LOG_TABLE
    try {
      await readEtlRunLog(['销售订单数据'], query)
      assert.match(seen[2], /FROM qiwei_new_bi\.etl_run_log/)
    } finally {
      if (previous == null) delete process.env.ETL_RUN_LOG_TABLE
      else process.env.ETL_RUN_LOG_TABLE = previous
    }
  })

  it('does not treat a database outage as an empty ETL log', () => {
    assert.equal(isMissingRunLog({ message: 'connect ECONNREFUSED 127.0.0.1:3306' }), false)
    assert.equal(isMissingRunLog({ code: 'ER_NO_SUCH_TABLE', message: "Table 'dataease.etl_run_log' doesn't exist" }), true)
  })

  it('marks an ETL hold so the same slot can be retried after a later success', () => {
    assert.equal(isEtlHoldSummary(`${ETL_HOLD_PREFIX}已阻止发送旧图`), true)
    assert.equal(isEtlHoldSummary('dry-run: send skipped'), false)
  })
})

describe('service logs do not keep secrets', () => {
  it('redacts passwords, secrets and bearer tokens', () => {
    const raw = 'password=Secret123 app_secret: abcdef Bearer t-very-secret-token token=t-anothersecret'
    const redacted = redactSecrets(raw)
    assert.equal(redacted.includes('Secret123'), false)
    assert.equal(redacted.includes('abcdef'), false)
    assert.equal(redacted.includes('t-very-secret-token'), false)
    assert.match(redacted, /password=\(redacted\)/)
    assert.match(redacted, /Bearer \(redacted\)/)
  })
})
