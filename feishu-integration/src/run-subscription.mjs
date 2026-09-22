import fs from 'fs'
import path from 'path'
import { fileURLToPath } from 'url'
import { CONFIG, ROOT_DIR, TMP_DIR } from './config.mjs'
import { isConfirmedDelivery, subscriptionLane } from './delivery-lane.mjs'
import { desiredExecutionMode, normalizeExecutionMode } from './execution-mode.mjs'
import { etlHoldSummary, freshnessDecision } from './etl-status.mjs'
import { parseSchedule } from './schedule.mjs'
import { recordServiceEvent } from './service-log.mjs'
import { closePool } from './db.mjs'
import { getTenantAccessToken, sendImage, uploadImage } from './feishu.mjs'
import {
  claimDeliveryRun,
  getAppConfigByAppId,
  getDeliveryRunById,
  getSubscriptionById,
  insertDeliveryTarget,
  listDeliveryTargets,
  listSubscriptionRecipients,
  persistExecutionMode,
  updateDeliveryRun,
  updateDeliveryTarget
} from './store.mjs'

const __filename = fileURLToPath(import.meta.url)

function stamp() {
  return new Date().toISOString()
}

function clipError(err, max = 500) {
  const text = typeof err === 'string' ? err : err?.message || String(err)
  return text.replace(/\s+/g, ' ').slice(0, max)
}

function parseArgs(argv) {
  const out = {
    subscriptionId: null,
    plannedAt: null,
    retryRunId: null,
    triggerType: 'MANUAL',
    operatorUserId: 1,
    failScreenshot: false,
    failUpload: false,
    failRecipient: null,
    reusePng: null,
    dryRun: false,
    closeResources: true
  }
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i]
    const next = argv[i + 1]
    if (arg === '--subscription-id') {
      out.subscriptionId = next
      i++
    } else if (arg === '--planned-at') {
      out.plannedAt = Number(next)
      i++
    } else if (arg === '--retry-run-id') {
      out.retryRunId = next
      i++
    } else if (arg === '--trigger-type') {
      out.triggerType = String(next || 'MANUAL').toUpperCase()
      i++
    } else if (arg === '--operator-user-id') {
      out.operatorUserId = Number(next)
      i++
    } else if (arg === '--fail-screenshot') {
      out.failScreenshot = true
    } else if (arg === '--fail-upload') {
      out.failUpload = true
    } else if (arg === '--fail-recipient') {
      out.failRecipient = String(next || '').toUpperCase()
      i++
    } else if (arg === '--reuse-png') {
      out.reusePng = next
      i++
    } else if (arg === '--dry-run') {
      out.dryRun = true
    }
  }
  return out
}

function resolveAppSecret(appSecretRef) {
  if (!appSecretRef) throw new Error('app_secret_ref is empty')
  if (appSecretRef.includes('/') || appSecretRef.includes('\\') || appSecretRef.includes('.')) {
    throw new Error('only env-var app_secret_ref is supported in Phase 2D')
  }
  const value = process.env[appSecretRef]
  if (!value) throw new Error(`env ${appSecretRef} is not set`)
  return value
}

function filtersFromSubscription(sub) {
  if (!sub.fixed_filter_json) return []
  try {
    const parsed = JSON.parse(sub.fixed_filter_json)
    const items = Array.isArray(parsed?.items) ? parsed.items : []
    return items
      .map(item => ({
        name: String(item.name || ''),
        value: Array.isArray(item.value) ? String(item.value[0] ?? '') : String(item.value ?? '')
      }))
      .filter(item => item.name && item.value)
  } catch {
    return []
  }
}

function recipientMatchesFailFlag(recipient, failRecipient) {
  if (!failRecipient) return false
  const flag = String(failRecipient).toUpperCase()
  return (
    flag === String(recipient.recipient_type || '').toUpperCase() ||
    flag === String(recipient.receive_id_type || '').toUpperCase() ||
    flag === String(recipient.id)
  )
}

function summarizeRun(run, targets) {
  const succeeded = targets.filter(row => row.status === 'SUCCEEDED').length
  const failed = targets.filter(row => row.status === 'FAILED' || row.status === 'UNKNOWN').length
  const pending = targets.filter(row => row.status === 'PENDING' || row.status === 'RUNNING').length
  return {
    runStatus: run.status,
    screenshotStatus: run.screenshot_status,
    uploadStatus: run.upload_status,
    targetCount: targets.length,
    succeeded,
    failed,
    pending,
    messageIds: targets.map(row => ({
      recipientId: String(row.recipient_id),
      type: row.recipient_type || row.receive_id_type,
      status: row.status,
      messageId: row.feishu_message_id
    }))
  }
}

async function aggregateRunStatus(runId, extraError) {
  const run = await getDeliveryRunById(runId)
  const targets = await listDeliveryTargets(runId)
  let status = 'SUCCEEDED'
  let errorSummary = extraError || null
  if (run.screenshot_status === 'FAILED' || run.upload_status === 'FAILED') {
    status = 'FAILED'
    errorSummary =
      extraError || run.screenshot_error || run.upload_error || 'screenshot or upload failed'
  } else if (!targets.length) {
    status = 'FAILED'
    errorSummary = extraError || 'no delivery targets'
  } else if (targets.some(row => row.status === 'FAILED' || row.status === 'UNKNOWN')) {
    const failed = targets.filter(row => row.status === 'FAILED' || row.status === 'UNKNOWN').length
    const succeeded = targets.filter(row => row.status === 'SUCCEEDED').length
    status = 'FAILED'
    errorSummary = extraError || `partial ${succeeded}/${targets.length} succeeded, ${failed} failed`
  } else if (targets.some(row => row.status !== 'SUCCEEDED')) {
    status = 'FAILED'
    errorSummary = extraError || 'incomplete targets'
  }
  await updateDeliveryRun(runId, {
    status,
    errorSummary,
    finishedAt: Date.now()
  })
  return getDeliveryRunById(runId)
}

function assertRunnable(sub, recipients, triggerType) {
  if (!sub) throw new Error('subscription not found')
  if (sub.status === 'DELETED' || sub.status === 'DISABLED') {
    throw new Error(`subscription status=${sub.status} is not runnable`)
  }
  const schedule = parseSchedule(sub.schedule_json)
  if (triggerType === 'SCHEDULED' && schedule.autoRun !== true) {
    throw new Error('scheduled trigger refused: subscription autoRun is disabled')
  }
  if (!sub.dashboard_id || !sub.chart_id) {
    throw new Error('subscription missing dashboard_id or chart_id')
  }
  if (!recipients.length) throw new Error('subscription has no recipients')
  for (const row of recipients) {
    if (row.status === 'DISABLED') {
      throw new Error(`recipient ${row.display_name} is DISABLED`)
    }
    if (!row.feishu_id || !row.receive_id_type) {
      throw new Error(`recipient ${row.display_name} missing feishu_id or receive_id_type`)
    }
  }
  return schedule
}

async function ensureTargets(run, recipients) {
  const existing = await listDeliveryTargets(run.id)
  const byRecipient = new Map(existing.map(row => [String(row.recipient_id), row]))
  const targets = []
  for (const recipient of recipients) {
    let target = byRecipient.get(String(recipient.id))
    if (!target) {
      const id = await insertDeliveryTarget({
        runId: run.id,
        recipientId: recipient.id,
        receiveIdType: recipient.receive_id_type,
        feishuId: recipient.feishu_id,
        status: 'PENDING'
      })
      const listed = await listDeliveryTargets(run.id)
      target = listed.find(row => String(row.id) === String(id)) || listed.find(row => String(row.recipient_id) === String(recipient.id))
    }
    targets.push({ target, recipient })
  }
  return targets
}

export async function startManualRun(opts) {
  const subscriptionId = opts.subscriptionId
  if (!subscriptionId) throw new Error('subscriptionId is required')
  const triggerType = opts.triggerType || 'MANUAL'
  const sub = await getSubscriptionById(subscriptionId)
  const recipients = await listSubscriptionRecipients(subscriptionId)
  assertRunnable(sub, recipients, triggerType)
  const executionMode = desiredExecutionMode({ dryRun: opts.dryRun === true, configDryRun: CONFIG.dryRun })
  return claimDeliveryRun({
    subscriptionId,
    triggerType,
    plannedAt: opts.plannedAt || Date.now(),
    operatorUserId: opts.operatorUserId ?? 1,
    executionMode
  })
}

async function completeDryRun(run, recipients, note) {
  const summary = note ? `dry-run: send skipped; ${note}` : 'dry-run: send skipped'
  await updateDeliveryRun(run.id, {
    status: 'RUNNING',
    executionMode: 'DRY_RUN',
    startedAt: Number(run.started_at) || Date.now(),
    screenshotStatus: 'SKIPPED',
    screenshotError: 'dry-run: screenshot skipped',
    uploadStatus: 'SKIPPED',
    uploadError: 'dry-run: upload skipped'
  })
  const pairs = await ensureTargets(run, recipients)
  for (const { target } of pairs) {
    if (isConfirmedDelivery(target, 'DRY_RUN')) continue
    await updateDeliveryTarget(target.id, {
      status: 'SUCCEEDED',
      feishuMessageId: null,
      errorSummary: 'dry-run: send skipped'
    })
  }
  await updateDeliveryRun(run.id, {
    status: 'SUCCEEDED',
    errorSummary: summary.slice(0, 500),
    finishedAt: Date.now()
  })
  return getDeliveryRunById(run.id)
}

export async function executeSubscription(opts) {
  const options = { closeResources: false, ...opts }
  const report = {
    startedAt: stamp(),
    dataClass: 'DEV',
    skipped: false,
    steps: {}
  }
  if (!options.subscriptionId && !options.retryRunId) {
    throw new Error('--subscription-id is required')
  }

  let run = null
  let created = false
  if (options.retryRunId) {
    run = await getDeliveryRunById(options.retryRunId)
    if (!run) throw new Error(`run ${options.retryRunId} not found`)
    created = false
    if (options.subscriptionId && String(run.subscription_id) !== String(options.subscriptionId)) {
      throw new Error('retry-run-id does not belong to subscription-id')
    }
    options.subscriptionId = String(run.subscription_id)
    report.idempotency = {
      mode: 'retry-run',
      runId: String(run.id),
      plannedAt: Number(run.planned_at)
    }
  } else {
    const plannedAt = options.plannedAt || Date.now()
    const claimed = await claimDeliveryRun({
      subscriptionId: options.subscriptionId,
      triggerType: options.triggerType || 'MANUAL',
      plannedAt,
      operatorUserId: options.operatorUserId ?? 1,
      executionMode: desiredExecutionMode({ dryRun: options.dryRun === true, configDryRun: CONFIG.dryRun })
    })
    run = claimed.run
    created = claimed.created
    report.idempotency = {
      mode: created ? 'new-run' : 'reuse-planned-at',
      runId: String(run.id),
      plannedAt,
      created
    }
  }

  const sub = await getSubscriptionById(options.subscriptionId)
  const recipients = await listSubscriptionRecipients(options.subscriptionId)
  const schedule = assertRunnable(sub, recipients, options.triggerType || run.trigger_type)
  report.subscription = {
    id: String(sub.id),
    name: sub.name,
    dashboardId: sub.dashboard_id,
    chartId: sub.chart_id,
    chartName: sub.chart_name,
    status: sub.status,
    autoRun: schedule.autoRun === true,
    scheduleKind: schedule.kind
  }
  report.recipients = recipients.map(row => ({
    id: String(row.id),
    type: row.recipient_type,
    receiveIdType: row.receive_id_type,
    verifyStatus: row.verify_status,
    displayName: row.display_name
  }))

  const existingTargets = await listDeliveryTargets(run.id)
  const lane = subscriptionLane(sub)
  const storedMode = normalizeExecutionMode(run.execution_mode)
  let desired = desiredExecutionMode({ dryRun: options.dryRun === true, configDryRun: CONFIG.dryRun })
  if (desired === 'LIVE' && lane !== 'PROD') {
    desired = 'DRY_RUN'
    report.liveRefused = 'test subscription cannot live-send'
  } else if (options.liveRefused) {
    report.liveRefused = 'test subscription cannot live-send'
  }
  const executionMode =
    storedMode === 'DRY_RUN' || storedMode === 'LIVE'
      ? storedMode
      : (await persistExecutionMode(run.id, desired)) || desired
  run.execution_mode = executionMode
  const dryRun = executionMode === 'DRY_RUN'
  report.executionMode = executionMode
  report.deliveryLane = lane
  if (storedMode === 'DRY_RUN' && desired === 'LIVE') {
    report.modeLocked = 'dry-run record cannot be converted to live send'
  }

  const liveComplete =
    executionMode === 'LIVE' &&
    run.status === 'SUCCEEDED' &&
    existingTargets.length > 0 &&
    existingTargets.every(row => isConfirmedDelivery(row, 'LIVE'))
  const dryComplete = executionMode === 'DRY_RUN' && run.status === 'SUCCEEDED'
  if (!created && (liveComplete || dryComplete)) {
    report.skipped = true
    report.reason =
      executionMode === 'DRY_RUN'
        ? 'dry-run already completed; refusing to upgrade to live send'
        : 'run already succeeded; refusing duplicate send'
    report.summary = summarizeRun(run, existingTargets)
    report.finishedAt = stamp()
    return report
  }

  if (executionMode === 'LIVE' && lane !== 'PROD') {
    await updateDeliveryRun(run.id, {
      status: 'SKIPPED',
      errorSummary: 'test subscription refused live send',
      finishedAt: Date.now()
    })
    report.skipped = true
    report.reason = 'test subscription refused live send'
    report.finishedAt = stamp()
    return report
  }

  let freshnessNote = null
  if ((options.triggerType || run.trigger_type) === 'SCHEDULED') {
    const freshness = await freshnessDecision(sub, { readRuns: options.readRuns })
    report.steps.freshness = {
      result: freshness.blocked ? 'BLOCKED' : freshness.checked ? 'PASS' : 'NOT_CHECKED',
      reason: freshness.reason,
      label: freshness.label,
      lastSuccessAt: freshness.lastSuccessAt || null
    }
    if (freshness.blocked) {
      await updateDeliveryRun(run.id, {
        status: 'SKIPPED',
        errorSummary: etlHoldSummary(freshness.reason),
        finishedAt: Date.now()
      })
      recordServiceEvent({
        area: 'etl-status',
        runId: run.id,
        message: freshness.reason
      })
      report.skipped = true
      report.reason = freshness.reason
      report.finishedAt = stamp()
      return report
    }
    if (!freshness.checked) freshnessNote = freshness.label
  }

  await updateDeliveryRun(run.id, {
    status: 'RUNNING',
    startedAt: Number(run.started_at) || Date.now()
  })

  try {
    if (dryRun) {
      const note = [report.liveRefused, freshnessNote].filter(Boolean).join('; ')
      const dry = await completeDryRun(run, recipients, note || null)
      const targets = await listDeliveryTargets(run.id)
      report.steps.screenshot = { result: 'SKIPPED', reason: 'dry-run' }
      report.steps.upload = { result: 'SKIPPED', reason: 'dry-run' }
      report.steps.send = { result: 'SKIPPED', reason: 'dry-run' }
      report.summary = summarizeRun(dry, targets)
      report.runId = String(dry.id)
      report.finishedAt = stamp()
      return report
    }

    if (run.screenshot_status !== 'SUCCEEDED' || !run.png_ref) {
      if (options.failScreenshot) {
        await updateDeliveryRun(run.id, {
          screenshotStatus: 'FAILED',
          screenshotError: 'simulated screenshot failure',
          screenshotAt: Date.now()
        })
        const failed = await aggregateRunStatus(run.id, 'screenshot failed; send skipped')
        report.steps.screenshot = { result: 'FAIL', simulated: true }
        report.steps.upload = { result: 'SKIPPED', reason: 'screenshot failed' }
        report.steps.send = { result: 'SKIPPED', reason: 'screenshot failed' }
        report.summary = summarizeRun(failed, await listDeliveryTargets(run.id))
        report.finishedAt = stamp()
        return report
      }

      let pngPath
      let captureMeta = null
      if (options.reusePng && fs.existsSync(options.reusePng)) {
        pngPath = options.reusePng
        captureMeta = { reused: options.reusePng, pngBytes: fs.statSync(pngPath).size }
      } else {
        const { captureCard } = await import('./capture.mjs')
        const shot = await captureCard({
          dashboardId: sub.dashboard_id,
          chartId: sub.chart_id,
          filters: filtersFromSubscription(sub),
          taskId: `run-${run.id}`
        })
        pngPath = shot.pngPath
        captureMeta = {
          pngBytes: shot.meta.pngBytes,
          pngHash: shot.meta.pngHash,
          filterMode: shot.meta.filterMode,
          durationMs: shot.meta.durationMs
        }
      }
      const storedPng = path.join(TMP_DIR, `run_${run.id}.png`)
      fs.mkdirSync(TMP_DIR, { recursive: true })
      if (path.resolve(pngPath) !== path.resolve(storedPng)) {
        fs.copyFileSync(pngPath, storedPng)
      }
      await updateDeliveryRun(run.id, {
        screenshotStatus: 'SUCCEEDED',
        screenshotError: null,
        screenshotAt: Date.now(),
        pngRef: storedPng
      })
      report.steps.screenshot = { result: 'PASS', ...captureMeta, pngRef: storedPng }
      run = await getDeliveryRunById(run.id)
    } else {
      report.steps.screenshot = { result: 'REUSED', pngRef: run.png_ref }
    }

    if (run.upload_status !== 'SUCCEEDED' || !run.image_key) {
      if (options.failUpload) {
        await updateDeliveryRun(run.id, {
          uploadStatus: 'FAILED',
          uploadError: 'simulated upload failure',
          uploadAt: Date.now()
        })
        const failed = await aggregateRunStatus(run.id, 'upload failed; send skipped')
        report.steps.upload = { result: 'FAIL', simulated: true }
        report.steps.send = { result: 'SKIPPED', reason: 'upload failed' }
        report.summary = summarizeRun(failed, await listDeliveryTargets(run.id))
        report.finishedAt = stamp()
        return report
      }

      const app = await getAppConfigByAppId(CONFIG.feishuAppId)
      if (!app) throw new Error('company_feishu_app_config not found for FEISHU_APP_ID')
      const secret = resolveAppSecret(app.app_secret_ref)
      const auth = await getTenantAccessToken(app.app_id, secret)
      report.steps.auth = { result: 'PASS', tokenRedacted: auth.meta.tokenRedacted, expire: auth.expire }
      const pngPath = run.png_ref
      if (!pngPath || !fs.existsSync(pngPath)) {
        throw new Error('png_ref missing after screenshot stage')
      }
      const uploaded = await uploadImage({
        token: auth.token,
        filePath: pngPath,
        filename: `run_${run.id}.png`
      })
      if (!uploaded.ok || !uploaded.imageKey) {
        await updateDeliveryRun(run.id, {
          uploadStatus: 'FAILED',
          uploadError: clipError(uploaded.msg || `upload http=${uploaded.http} code=${uploaded.code}`),
          uploadAt: Date.now()
        })
        const failed = await aggregateRunStatus(run.id, 'upload failed; send skipped')
        report.steps.upload = { result: 'FAIL', http: uploaded.http, code: uploaded.code, msg: uploaded.msg }
        report.steps.send = { result: 'SKIPPED', reason: 'upload failed' }
        report.summary = summarizeRun(failed, await listDeliveryTargets(run.id))
        report.finishedAt = stamp()
        return report
      }
      await updateDeliveryRun(run.id, {
        uploadStatus: 'SUCCEEDED',
        uploadError: null,
        uploadAt: Date.now(),
        imageKey: uploaded.imageKey
      })
      report.steps.upload = { result: 'PASS', bytes: uploaded.bytes, imageKey: uploaded.imageKey }
      run = await getDeliveryRunById(run.id)
      run._token = auth.token
    } else {
      report.steps.upload = { result: 'REUSED', imageKey: run.image_key }
    }

    let token = run._token
    if (!token) {
      const app = await getAppConfigByAppId(CONFIG.feishuAppId)
      const secret = resolveAppSecret(app.app_secret_ref)
      const auth = await getTenantAccessToken(app.app_id, secret)
      token = auth.token
      report.steps.auth = { result: 'PASS', tokenRedacted: auth.meta.tokenRedacted, expire: auth.expire }
    }

    const pairs = await ensureTargets(run, recipients)
    const sendResults = []
    for (const { target, recipient } of pairs) {
      if (isConfirmedDelivery(target, 'LIVE')) {
        sendResults.push({
          recipientId: String(recipient.id),
          type: recipient.recipient_type,
          result: 'SKIPPED',
          reason: 'target already succeeded',
          messageId: target.feishu_message_id
        })
        continue
      }
      await updateDeliveryTarget(target.id, {
        status: 'RUNNING',
        retryCount: Number(target.retry_count || 0) + (target.status === 'FAILED' || target.status === 'UNKNOWN' ? 1 : 0)
      })
      if (recipientMatchesFailFlag(recipient, options.failRecipient)) {
        await updateDeliveryTarget(target.id, {
          status: 'FAILED',
          errorSummary: 'simulated recipient send failure',
          feishuMessageId: null
        })
        sendResults.push({
          recipientId: String(recipient.id),
          type: recipient.recipient_type,
          result: 'FAIL',
          simulated: true
        })
        continue
      }
      const sent = await sendImage({
        token,
        receiveIdType: recipient.receive_id_type,
        receiveId: recipient.feishu_id,
        imageKey: run.image_key
      })
      if (sent.ok && sent.messageId) {
        await updateDeliveryTarget(target.id, {
          status: 'SUCCEEDED',
          feishuMessageId: sent.messageId,
          errorSummary: null,
          sentAt: Date.now()
        })
        sendResults.push({
          recipientId: String(recipient.id),
          type: recipient.recipient_type,
          result: 'PASS',
          messageId: sent.messageId
        })
      } else if (sent.http === 200 && !sent.messageId) {
        await updateDeliveryTarget(target.id, {
          status: 'UNKNOWN',
          errorSummary: clipError(sent.msg || 'http 200 without message_id'),
          sentAt: Date.now()
        })
        sendResults.push({
          recipientId: String(recipient.id),
          type: recipient.recipient_type,
          result: 'UNKNOWN',
          http: sent.http,
          code: sent.code,
          msg: sent.msg
        })
      } else {
        await updateDeliveryTarget(target.id, {
          status: 'FAILED',
          errorSummary: clipError(sent.msg || `send http=${sent.http} code=${sent.code}`)
        })
        sendResults.push({
          recipientId: String(recipient.id),
          type: recipient.recipient_type,
          result: 'FAIL',
          http: sent.http,
          code: sent.code,
          msg: sent.msg
        })
      }
    }

    const finalRun = await aggregateRunStatus(run.id)
    const targets = await listDeliveryTargets(run.id)
    report.steps.send = { result: targets.every(row => row.status === 'SUCCEEDED') ? 'PASS' : 'FAIL', items: sendResults }
    report.summary = summarizeRun(finalRun, targets)
    report.runId = String(finalRun.id)
    report.finishedAt = stamp()
    return report
  } catch (err) {
    const summary = clipError(err)
    recordServiceEvent({ area: 'delivery', runId: run.id, message: summary, code: err.code || null })
    await updateDeliveryRun(run.id, {
      status: 'FAILED',
      errorSummary: summary,
      finishedAt: Date.now()
    }).catch(dbErr => {
      recordServiceEvent({ area: 'database', runId: run.id, message: dbErr.message })
    })
    report.error = summary
    report.summary = summarizeRun(await getDeliveryRunById(run.id), await listDeliveryTargets(run.id))
    report.finishedAt = stamp()
    throw Object.assign(err, { report })
  } finally {
    if (options.closeResources !== false) {
      const { closeBrowser } = await import('./session.mjs')
      await closeBrowser().catch(() => {})
      await closePool().catch(() => {})
    }
  }
}

async function main() {
  fs.mkdirSync(TMP_DIR, { recursive: true })
  const options = parseArgs(process.argv.slice(2))
  const report = await executeSubscription({ ...options, closeResources: true })
  const out = path.join(ROOT_DIR, 'tmp', 'phase2d_last_run.json')
  fs.writeFileSync(out, JSON.stringify(report, null, 2))
  console.log(`[phase2d] wrote ${out}`)
  console.log(`skipped=${report.skipped}`)
  console.log(`run_id=${report.idempotency?.runId || report.runId || ''}`)
  console.log(`run_status=${report.summary?.runStatus || ''}`)
  console.log(`screenshot=${report.steps.screenshot?.result || ''}`)
  console.log(`upload=${report.steps.upload?.result || ''}`)
  console.log(`send=${report.steps.send?.result || ''}`)
  if (report.summary?.messageIds) {
    for (const item of report.summary.messageIds) {
      console.log(`target type=${item.type} status=${item.status} message_id=${item.messageId || ''}`)
    }
  }
}

const isMain =
  Boolean(process.argv[1]) &&
  path.normalize(__filename) === path.normalize(path.resolve(process.argv[1]))

if (isMain) {
  main().catch(err => {
    console.error('[phase2d] ERROR', err.message)
    if (err.report) {
      fs.mkdirSync(TMP_DIR, { recursive: true })
      fs.writeFileSync(path.join(ROOT_DIR, 'tmp', 'phase2d_last_run.json'), JSON.stringify(err.report, null, 2))
    }
    process.exit(1)
  })
}
