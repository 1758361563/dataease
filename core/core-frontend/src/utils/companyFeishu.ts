export type FeishuRecipientType = 'PERSONAL' | 'CHAT'
export type FeishuReceiveIdType = 'user_id' | 'chat_id'
export type FeishuVerifyStatus = 'UNVERIFIED' | 'VERIFIED' | 'FAILED'
export type FeishuEnableStatus = 'ENABLED' | 'DISABLED'
export type FeishuSubscriptionStatus = 'DRAFT' | 'ENABLED' | 'DISABLED' | 'DELETED'

export type FeishuIdentityStatus = 'UNCONFIRMED' | 'CONFIRMED'

export function receiveIdTypeFor(recipientType: FeishuRecipientType): FeishuReceiveIdType {
  return recipientType === 'CHAT' ? 'chat_id' : 'user_id'
}

export function recipientTypeLabel(type?: string): string {
  if (type === 'CHAT') return '群聊'
  if (type === 'PERSONAL') return '个人'
  return type || '-'
}

export function receiveIdTypeLabel(type?: string): string {
  if (type === 'chat_id') return 'chat_id（群）'
  if (type === 'user_id') return 'user_id（个人）'
  return type || '-'
}

export function verifyStatusLabel(status?: string): string {
  if (status === 'VERIFIED') return '发送已验证'
  if (status === 'FAILED') return '发送失败'
  return '发送未验证'
}

export function identityStatusLabel(status?: string): string {
  if (status === 'CONFIRMED') return '身份已确认'
  return '身份未确认'
}

export function isIdentityConfirmed(status?: string): boolean {
  return status === 'CONFIRMED'
}

export function employeeMatchLabel(row: {
  name?: string
  departments?: string[]
  userIdTail?: string
}): string {
  const dept = (row.departments || []).filter(Boolean).join(' / ') || '部门未知'
  const tail = row.userIdTail ? `…${row.userIdTail}` : ''
  return `${row.name || '-'} · ${dept}${tail ? ` · ${tail}` : ''}`
}

export function isRecipientVerified(status?: string): boolean {
  return status === 'VERIFIED'
}

export function subscriptionStatusLabel(status?: string): string {
  if (status === 'ENABLED') return '启用'
  if (status === 'DISABLED') return '停用'
  if (status === 'DELETED') return '已删除'
  return '草稿'
}

export type FeishuExecutionMode = 'DRY_RUN' | 'LIVE' | 'UNKNOWN'

export function inferExecutionMode(run?: {
  executionMode?: string | null
  errorSummary?: string | null
  screenshotStatus?: string | null
  uploadStatus?: string | null
  targets?: Array<{ messageId?: string | null; errorSummary?: string | null; status?: string }>
}): FeishuExecutionMode {
  const explicit = String(run?.executionMode || '').toUpperCase()
  if (explicit === 'DRY_RUN' || explicit === 'LIVE' || explicit === 'UNKNOWN') {
    return explicit
  }
  const texts = [run?.errorSummary, ...(run?.targets || []).map(item => item.errorSummary)]
  if (
    texts.some(text =>
      String(text || '')
        .toLowerCase()
        .includes('dry-run')
    )
  ) {
    return 'DRY_RUN'
  }
  if ((run?.targets || []).some(item => !!item.messageId)) {
    return 'LIVE'
  }
  return 'UNKNOWN'
}

export function inferTargetExecutionMode(
  target?: {
    messageId?: string | null
    errorSummary?: string | null
    executionMode?: string | null
    status?: string
  },
  runMode?: FeishuExecutionMode
): FeishuExecutionMode {
  const explicit = String(target?.executionMode || '').toUpperCase()
  if (explicit === 'DRY_RUN' || explicit === 'LIVE' || explicit === 'UNKNOWN') {
    return explicit
  }
  if (
    String(target?.errorSummary || '')
      .toLowerCase()
      .includes('dry-run') ||
    runMode === 'DRY_RUN'
  ) {
    return 'DRY_RUN'
  }
  if (runMode === 'LIVE' || target?.messageId) {
    return 'LIVE'
  }
  return 'UNKNOWN'
}

export function runStatusLabel(status?: string, executionMode?: FeishuExecutionMode): string {
  if (status === 'SUCCEEDED') {
    if (executionMode === 'DRY_RUN') return '模拟执行成功'
    if (executionMode === 'LIVE') return '真实发送成功'
    return '执行成功（发送方式未记录）'
  }
  if (status === 'FAILED') {
    if (executionMode === 'DRY_RUN') return '模拟执行失败'
    if (executionMode === 'LIVE') return '真实发送失败'
    return '失败'
  }
  if (status === 'RUNNING') return '执行中'
  if (status === 'SKIPPED') return '跳过'
  if (status === 'UNKNOWN') return '未知'
  return status || '受理中'
}

export function targetStatusLabel(status?: string, executionMode?: FeishuExecutionMode): string {
  return runStatusLabel(status, executionMode)
}

export function acceptedRunNotice(payload: { runId?: string; status?: string }): string {
  const runId = payload.runId || '-'
  const status = payload.status || 'PENDING'
  return `已受理，runId=${runId}，当前状态 ${status}。请查询 run/target 实际结果，不要当作发送完成。`
}

export function recipientOptionLabel(row: {
  displayName?: string
  recipientType?: string
  receiveIdType?: string
  feishuId?: string
  identityStatus?: string
  verifyStatus?: string
}): string {
  const idTail = row.feishuId ? row.feishuId.slice(-6) : '-'
  return `${row.displayName || '-'} · ${recipientTypeLabel(row.recipientType)} · ${
    row.receiveIdType || '-'
  } · …${idTail} · ${identityStatusLabel(row.identityStatus)} · ${verifyStatusLabel(
    row.verifyStatus
  )}`
}
