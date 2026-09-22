import { UNCHECKED_LABEL } from './etl-status.mjs'

export function subscriptionLane(sub) {
  const raw = String(sub?.delivery_lane || sub?.deliveryLane || 'TEST').trim().toUpperCase()
  return raw === 'PROD' ? 'PROD' : 'TEST'
}

export function resolveSchedulerDryRun(opts = {}, configDryRun = true) {
  if (opts.dryRun === true) return true
  if (configDryRun !== false) return true
  return false
}

export function claimModeForSubscription({ globalDryRun, lane }) {
  const prod = lane === 'PROD'
  if (globalDryRun || !prod) {
    return {
      dryRun: true,
      executionMode: 'DRY_RUN',
      liveRefused: globalDryRun === false && !prod
    }
  }
  return { dryRun: false, executionMode: 'LIVE', liveRefused: false }
}

export function planScheduledSend({ lane, globalDryRun, freshness }) {
  const claim = claimModeForSubscription({ globalDryRun, lane })
  if (freshness?.blocked) {
    return {
      ...claim,
      hold: true,
      dataUpdateLabel: freshness.label || '关联任务未成功',
      reason: freshness.reason
    }
  }
  const checked = freshness?.checked === true
  return {
    ...claim,
    hold: false,
    dataUpdateLabel: checked ? freshness.label : UNCHECKED_LABEL,
    reason: checked ? freshness.reason : UNCHECKED_LABEL
  }
}

export function isConfirmedDelivery(target, executionMode) {
  if (target?.status !== 'SUCCEEDED') return false
  if (executionMode === 'DRY_RUN') return true
  return Boolean(target.feishu_message_id || target.messageId)
}

export function isPartialRetryCandidate(run, targets, maxRetries = 3) {
  if (!run || run.status !== 'FAILED') return false
  const mode = String(run.execution_mode || run.executionMode || '').toUpperCase()
  const confirmed = (targets || []).some(row => isConfirmedDelivery(row, mode))
  const retryable = (targets || []).some(row => {
    const status = row.status
    const retries = Number(row.retry_count ?? row.retryCount ?? 0)
    return (status === 'FAILED' || status === 'UNKNOWN') && retries < maxRetries
  })
  return confirmed && retryable
}
