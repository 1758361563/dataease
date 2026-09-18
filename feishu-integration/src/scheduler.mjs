import { CONFIG } from './config.mjs'
import { currentDueSlot, freshnessDecision, parseSchedule } from './schedule.mjs'
import {
  claimDeliveryRun,
  listEnabledSubscriptions,
  listIncompleteDeliveryRuns,
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
      recovered.push({ runId: String(run.id), error: err.message })
    }
  }
  return recovered
}

export async function fireDueSubscriptions({ now = Date.now(), dryRun, execute } = {}) {
  const executeSubscription = execute || (await import('./run-subscription.mjs')).executeSubscription
  const useDryRun = dryRun !== false
  const subs = await listEnabledSubscriptions()
  const fired = []
  for (const sub of subs) {
    const schedule = parseSchedule(sub.schedule_json)
    const slot = currentDueSlot(now, schedule)
    if (!slot) continue
    const freshness = freshnessDecision(sub)
    const claimed = await claimDeliveryRun({
      subscriptionId: sub.id,
      triggerType: 'SCHEDULED',
      plannedAt: slot,
      operatorUserId: null,
      executionMode: useDryRun ? 'DRY_RUN' : 'LIVE'
    })
    if (!claimed.created && terminalStatus(claimed.run.status)) {
      fired.push({ subscriptionId: String(sub.id), runId: String(claimed.run.id), skipped: true, reason: 'slot-already-terminal' })
      continue
    }
    if (freshness.blocked) {
      await updateDeliveryRun(claimed.run.id, {
        status: 'SKIPPED',
        errorSummary: freshness.reason,
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
      dryRun: useDryRun,
      closeResources: false
    })
    fired.push({
      subscriptionId: String(sub.id),
      runId: String(claimed.run.id),
      created: claimed.created,
      skipped: !!report.skipped,
      executionMode: report.executionMode
    })
  }
  return fired
}

export async function schedulerTick(opts = {}) {
  return withSchedulerLock(async () => {
    const dryRun = opts.dryRun !== false && (opts.dryRun === true || CONFIG.dryRun !== false)
    const recovered = await recoverIncompleteRuns({ ...opts, dryRun })
    const fired = await fireDueSubscriptions({ ...opts, dryRun })
    return { dryRun, recovered, fired }
  })
}

export function startScheduler({ intervalMs } = {}) {
  const ms = Number(intervalMs || CONFIG.schedulerIntervalMs || 30_000)
  const tick = () =>
    schedulerTick().catch(err => {
      console.error('[feishu-shot] scheduler tick failed', err.message)
    })
  tick()
  const timer = setInterval(tick, ms)
  if (typeof timer.unref === 'function') timer.unref()
  return timer
}
