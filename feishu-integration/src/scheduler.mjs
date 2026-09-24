import { CONFIG } from './config.mjs'
import { planScheduledSend, resolveSchedulerDryRun, subscriptionLane } from './delivery-lane.mjs'
import { etlHoldSummary, freshnessDecision, isEtlHoldSummary } from './etl-status.mjs'
import { currentDueSlot, parseSchedule } from './schedule.mjs'
import { listEnabledSheetSources } from './sheet-source.mjs'
import { syncConfiguredSource } from './sheet-sync.mjs'
import { recordServiceEvent } from './service-log.mjs'
import {
  claimDeliveryRun,
  listEnabledSubscriptions,
  listIncompleteDeliveryRuns,
  listPartialRetryRuns,
  updateDeliveryRun,
  withSchedulerLock
} from './store.mjs'

function terminalStatus(status) {
  return ['SUCCEEDED', 'FAILED', 'SKIPPED', 'UNKNOWN'].includes(String(status || ''))
}

export async function recoverIncompleteRuns({ dryRun, execute } = {}) {
  const executeSubscription = execute || (await import('./run-subscription.mjs')).executeSubscription
  const rows = await listIncompleteDeliveryRuns(50)
  const recovered = []
  for (const run of rows) {
    try {
      const report = await executeSubscription({
        retryRunId: String(run.id),
        subscriptionId: String(run.subscription_id),
        triggerType: run.trigger_type,
        dryRun: dryRun !== false,
        closeResources: false
      })
      recovered.push({ runId: String(run.id), skipped: !!report.skipped, status: report.summary?.runStatus })
    } catch (err) {
      recordServiceEvent({ area: 'scheduler', runId: run.id, message: err.message })
      recovered.push({ runId: String(run.id), error: err.message })
    }
  }
  return recovered
}

export async function retryPartialFailures({ dryRun, execute, maxRetries = 3 } = {}) {
  const executeSubscription = execute || (await import('./run-subscription.mjs')).executeSubscription
  const rows = await listPartialRetryRuns(maxRetries)
  const retried = []
  for (const run of rows) {
    try {
      const report = await executeSubscription({
        retryRunId: String(run.id),
        subscriptionId: String(run.subscription_id),
        triggerType: run.trigger_type,
        dryRun: dryRun !== false,
        closeResources: false
      })
      retried.push({ runId: String(run.id), skipped: !!report.skipped, status: report.summary?.runStatus })
    } catch (err) {
      recordServiceEvent({ area: 'scheduler', runId: run.id, message: err.message })
      retried.push({ runId: String(run.id), error: err.message })
    }
  }
  return retried
}

export async function fireDueSubscriptions({ now = Date.now(), dryRun, execute, readRuns } = {}) {
  const executeSubscription = execute || (await import('./run-subscription.mjs')).executeSubscription
  const globalDryRun = dryRun !== false
  const subs = await listEnabledSubscriptions()
  const fired = []
  for (const sub of subs) {
    const schedule = parseSchedule(sub.schedule_json)
    const slot = currentDueSlot(now, schedule)
    if (!slot) continue
    const freshness = await freshnessDecision(sub, { readRuns })
    const plan = planScheduledSend({
      lane: subscriptionLane(sub),
      globalDryRun,
      freshness
    })
    const claimed = await claimDeliveryRun({
      subscriptionId: sub.id,
      triggerType: 'SCHEDULED',
      plannedAt: slot,
      operatorUserId: null,
      executionMode: plan.executionMode
    })
    if (!claimed.created && terminalStatus(claimed.run.status)) {
      const waitingForEtl = claimed.run.status === 'SKIPPED' && isEtlHoldSummary(claimed.run.error_summary)
      if (!waitingForEtl) {
        fired.push({ subscriptionId: String(sub.id), runId: String(claimed.run.id), skipped: true, reason: 'slot-already-terminal' })
        continue
      }
      if (plan.hold) {
        await updateDeliveryRun(claimed.run.id, {
          status: 'SKIPPED',
          errorSummary: etlHoldSummary(freshness.reason),
          finishedAt: Date.now()
        })
        fired.push({
          subscriptionId: String(sub.id),
          runId: String(claimed.run.id),
          skipped: true,
          reason: freshness.reason
        })
        continue
      }
      await updateDeliveryRun(claimed.run.id, {
        status: 'PENDING',
        errorSummary: null,
        finishedAt: null
      })
    }
    if (plan.hold) {
      await updateDeliveryRun(claimed.run.id, {
        status: 'SKIPPED',
        errorSummary: etlHoldSummary(freshness.reason),
        finishedAt: Date.now()
      })
      fired.push({
        subscriptionId: String(sub.id),
        runId: String(claimed.run.id),
        skipped: true,
        reason: freshness.reason
      })
      continue
    }
    const report = await executeSubscription({
      retryRunId: String(claimed.run.id),
      subscriptionId: String(sub.id),
      triggerType: 'SCHEDULED',
      plannedAt: slot,
      dryRun: plan.dryRun,
      liveRefused: plan.liveRefused,
      readRuns,
      closeResources: false
    })
    fired.push({
      subscriptionId: String(sub.id),
      runId: String(claimed.run.id),
      created: claimed.created,
      skipped: !!report.skipped,
      executionMode: report.executionMode,
      liveRefused: !!plan.liveRefused,
      hold: plan.hold,
      dataUpdateLabel: plan.dataUpdateLabel
    })
  }
  return fired
}

export async function schedulerTick(opts = {}) {
  return withSchedulerLock(async () => {
    const dryRun = resolveSchedulerDryRun(opts, CONFIG.dryRun)
    const recovered = await recoverIncompleteRuns({ ...opts, dryRun })
    const retried = await retryPartialFailures({ ...opts, dryRun })
    const sheets = await fireDueSheetSources({ now: opts.now })
    const fired = await fireDueSubscriptions({ ...opts, dryRun, readRuns: opts.readRuns })
    return { dryRun, recovered, retried, fired, sheets }
  })
}

export async function fireDueSheetSources({ now = Date.now() } = {}) {
  const sources = await listEnabledSheetSources()
  const fired = []
  for (const source of sources) {
    const slot = currentDueSlot(now, source.schedule_json)
    if (!slot) continue
    try {
      const report = await syncConfiguredSource(source, slot)
      fired.push({
        source: source.source_name,
        skipped: !!report.skipped,
        reason: report.reason || null,
        rows: report.summary ? report.summary.rows : null
      })
    } catch (err) {
      recordServiceEvent({ area: 'sheet-sync', message: err.message })
      fired.push({ source: source.source_name, error: String(err.message || err).slice(0, 180) })
    }
  }
  return fired
}

export function startScheduler({ intervalMs } = {}) {
  const ms = Number(intervalMs || CONFIG.schedulerIntervalMs || 30_000)
  const tick = () =>
    schedulerTick().catch(err => {
      recordServiceEvent({ area: 'scheduler', message: err.message })
      console.error('[feishu-shot] scheduler tick failed', err.message)
    })
  tick()
  const timer = setInterval(tick, ms)
  if (typeof timer.unref === 'function') timer.unref()
  return timer
}
