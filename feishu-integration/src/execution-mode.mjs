export function inferExecutionMode(run = {}, targets = []) {
  const texts = [
    run.error_summary || run.errorSummary,
    ...(targets || []).map(row => row.error_summary || row.errorSummary)
  ]
  if (texts.some(text => String(text || '').toLowerCase().includes('dry-run'))) {
    return 'DRY_RUN'
  }
  if ((targets || []).some(row => row.feishu_message_id || row.messageId)) {
    return 'LIVE'
  }
  const screenshot = run.screenshot_status || run.screenshotStatus
  const upload = run.upload_status || run.uploadStatus
  if (screenshot === 'SUCCEEDED' && upload === 'SUCCEEDED') {
    return 'LIVE'
  }
  return 'UNKNOWN'
}

export function inferTargetExecutionMode(target = {}, runMode) {
  const text = String(target.error_summary || target.errorSummary || '').toLowerCase()
  if (text.includes('dry-run') || runMode === 'DRY_RUN') return 'DRY_RUN'
  if (target.feishu_message_id || target.messageId || runMode === 'LIVE') return 'LIVE'
  return 'UNKNOWN'
}
