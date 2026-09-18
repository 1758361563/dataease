export const EXECUTION_MODES = ['DRY_RUN', 'LIVE', 'UNKNOWN']

export function normalizeExecutionMode(value) {
  const mode = String(value || '').trim().toUpperCase()
  return EXECUTION_MODES.includes(mode) ? mode : null
}

function textsOf(run, targets) {
  return [run?.error_summary || run?.errorSummary, ...(targets || []).map(row => row.error_summary || row.errorSummary)]
}

function hasDryRunEvidence(run = {}, targets = []) {
  return textsOf(run, targets).some(text => String(text || '').toLowerCase().includes('dry-run'))
}

function hasLiveSendEvidence(targets = []) {
  return (targets || []).some(row => row.feishu_message_id || row.messageId)
}

export function desiredExecutionMode({ dryRun, configDryRun } = {}) {
  if (dryRun === true) return 'DRY_RUN'
  if (configDryRun !== false) return 'DRY_RUN'
  return 'LIVE'
}

export function inferExecutionMode(run = {}, targets = []) {
  const stored = normalizeExecutionMode(run.execution_mode || run.executionMode)
  if (stored) return stored
  if (hasDryRunEvidence(run, targets)) return 'DRY_RUN'
  if (hasLiveSendEvidence(targets)) return 'LIVE'
  return 'UNKNOWN'
}

export function inferTargetExecutionMode(target = {}, runMode) {
  const stored = normalizeExecutionMode(target.execution_mode || target.executionMode)
  if (stored) return stored
  const text = String(target.error_summary || target.errorSummary || '').toLowerCase()
  if (text.includes('dry-run') || runMode === 'DRY_RUN') return 'DRY_RUN'
  if (runMode === 'LIVE' || target.feishu_message_id || target.messageId) return 'LIVE'
  return 'UNKNOWN'
}

export function canUpgradeExecutionMode(fromMode, toMode) {
  const from = normalizeExecutionMode(fromMode)
  const to = normalizeExecutionMode(toMode)
  if (from === 'DRY_RUN' && to === 'LIVE') return false
  return true
}
