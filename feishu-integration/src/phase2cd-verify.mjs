import fs from 'fs'
import path from 'path'
import { ROOT_DIR, TMP_DIR, ensureDirs } from './config.mjs'
import { closePool } from './db.mjs'
import { closeBrowser } from './session.mjs'
import { seedDevConfig } from './seed-dev-config.mjs'
import { executeSubscription } from './run-subscription.mjs'
import { getDeliveryRunById, listDeliveryTargets } from './store.mjs'

function stamp() {
  return new Date().toISOString()
}

function assert(cond, msg) {
  if (!cond) throw new Error(msg)
}

async function main() {
  ensureDirs()
  const report = {
    startedAt: stamp(),
    dataClass: 'DEV',
    tests: {}
  }
  try {
    const seed = await seedDevConfig()
    report.seed = {
      subscriptionId: seed.subscription.id,
      personalId: seed.personal.id,
      chatId: seed.chat.id,
      identityMapVerified: seed.identity.identityMapVerified,
      identityMapScope: seed.identity.identityMapScope,
      autoRun: seed.autoRun
    }
    assert(seed.autoRun === false, 'dev subscription must disable autoRun')
    assert(seed.subscription.recipients.length === 2, 'dev subscription must bind personal and chat')

    const plannedAt = Date.now()
    const happy = await executeSubscription({
      subscriptionId: seed.subscription.id,
      plannedAt,
      triggerType: 'MANUAL',
      closeResources: false
    })
    report.tests.t1_db_read_and_run = {
      result: happy.summary?.runStatus === 'SUCCEEDED' ? 'PASS' : 'FAIL',
      runId: happy.idempotency.runId,
      skipped: happy.skipped,
      screenshot: happy.steps.screenshot?.result,
      upload: happy.steps.upload?.result,
      send: happy.steps.send?.result
    }
    const happyTargets = await listDeliveryTargets(happy.idempotency.runId)
    const messageIds = happyTargets.map(row => row.feishu_message_id).filter(Boolean)
    const chatTarget = happyTargets.find(row => row.receive_id_type === 'chat_id')
    const personalTarget = happyTargets.find(row => row.receive_id_type === 'user_id')
    report.tests.t2_chat_received = {
      result: chatTarget?.status === 'SUCCEEDED' && chatTarget?.feishu_message_id ? 'PASS' : 'FAIL',
      messageId: chatTarget?.feishu_message_id || null
    }
    report.tests.t3_personal_received = {
      result: personalTarget?.status === 'SUCCEEDED' && personalTarget?.feishu_message_id ? 'PASS' : 'FAIL',
      messageId: personalTarget?.feishu_message_id || null
    }
    report.tests.t4_db_records = {
      result:
        happyTargets.length === 2 && messageIds.length === 2 && happy.summary?.runStatus === 'SUCCEEDED'
          ? 'PASS'
          : 'FAIL',
      runId: happy.idempotency.runId,
      targets: happyTargets.map(row => ({
        type: row.receive_id_type,
        status: row.status,
        messageId: row.feishu_message_id
      }))
    }

    const pngRef = happy.steps.screenshot?.pngRef
    const partialPlannedAt = plannedAt + 1
    const partial = await executeSubscription({
      subscriptionId: seed.subscription.id,
      plannedAt: partialPlannedAt,
      triggerType: 'MANUAL',
      failRecipient: 'CHAT',
      reusePng: pngRef,
      closeResources: false
    })
    const partialTargets = await listDeliveryTargets(partial.idempotency.runId)
    const partialChat = partialTargets.find(row => row.receive_id_type === 'chat_id')
    const partialPerson = partialTargets.find(row => row.receive_id_type === 'user_id')
    report.tests.t5_partial_recipient_failure = {
      result:
        partial.summary?.runStatus === 'FAILED' &&
        partialChat?.status === 'FAILED' &&
        partialPerson?.status === 'SUCCEEDED' &&
        Boolean(partialPerson?.feishu_message_id)
          ? 'PASS'
          : 'FAIL',
      runId: partial.idempotency.runId,
      runStatus: partial.summary?.runStatus,
      chat: { status: partialChat?.status, messageId: partialChat?.feishu_message_id || null },
      personal: { status: partialPerson?.status, messageId: partialPerson?.feishu_message_id || null }
    }

    const shotFailPlannedAt = plannedAt + 2
    const shotFail = await executeSubscription({
      subscriptionId: seed.subscription.id,
      plannedAt: shotFailPlannedAt,
      triggerType: 'MANUAL',
      failScreenshot: true,
      closeResources: false
    })
    const shotFailTargets = await listDeliveryTargets(shotFail.idempotency.runId)
    const shotFailRun = await getDeliveryRunById(shotFail.idempotency.runId)
    report.tests.t6_screenshot_failure_stops_send = {
      result:
        shotFailRun.screenshot_status === 'FAILED' &&
        !shotFailRun.image_key &&
        shotFail.steps.upload?.result === 'SKIPPED' &&
        shotFail.steps.send?.result === 'SKIPPED' &&
        shotFailTargets.length === 0
          ? 'PASS'
          : 'FAIL',
      runId: shotFail.idempotency.runId,
      screenshotStatus: shotFailRun.screenshot_status,
      uploadStatus: shotFailRun.upload_status,
      imageKey: shotFailRun.image_key,
      targetCount: shotFailTargets.length
    }

    const replay = await executeSubscription({
      subscriptionId: seed.subscription.id,
      plannedAt,
      triggerType: 'MANUAL',
      closeResources: false
    })
    const replayTargets = await listDeliveryTargets(replay.idempotency.runId)
    report.tests.t7a_same_planned_at_no_resend = {
      result:
        replay.skipped === true &&
        String(replay.idempotency.runId) === String(happy.idempotency.runId) &&
        replayTargets.every(row => row.status === 'SUCCEEDED')
          ? 'PASS'
          : 'FAIL',
      skipped: replay.skipped,
      runId: replay.idempotency.runId
    }

    const retry = await executeSubscription({
      subscriptionId: seed.subscription.id,
      retryRunId: partial.idempotency.runId,
      failRecipient: 'CHAT',
      reusePng: pngRef,
      closeResources: false
    })
    const retryTargets = await listDeliveryTargets(retry.idempotency.runId)
    const retryPerson = retryTargets.find(row => row.receive_id_type === 'user_id')
    const retryChat = retryTargets.find(row => row.receive_id_type === 'chat_id')
    const personUnchanged = retryPerson?.feishu_message_id === partialPerson?.feishu_message_id
    const skippedPerson = (retry.steps.send?.items || []).some(
      item => item.type === 'PERSONAL' && item.result === 'SKIPPED'
    )
    report.tests.t7b_retry_skips_succeeded_target = {
      result:
        String(retry.idempotency.runId) === String(partial.idempotency.runId) &&
        retryPerson?.status === 'SUCCEEDED' &&
        personUnchanged &&
        skippedPerson &&
        retryChat?.status === 'FAILED'
          ? 'PASS'
          : 'FAIL',
      runId: retry.idempotency.runId,
      personalMessageId: retryPerson?.feishu_message_id || null,
      chatStatus: retryChat?.status
    }

    report.tests.t8_dev_marker = {
      result: seed.subscription.id && String(seed.autoRun) === 'false' ? 'PASS' : 'FAIL',
      subscriptionName: '[DEV] 全渠道系列商品排行表-手动测试',
      note: '开发测试数据保留在 mysql-de；非正式配置。执行日志见 tmp/phase2cd_report.json'
    }

    const failed = Object.entries(report.tests).filter(([, value]) => value.result !== 'PASS')
    report.result = failed.length ? 'FAIL' : 'PASS'
    report.failedTests = failed.map(([name]) => name)
    if (report.result !== 'PASS') {
      throw new Error(`phase2cd tests failed: ${report.failedTests.join(', ')}`)
    }
  } catch (err) {
    report.result = 'FAIL'
    report.error = err.message
    throw err
  } finally {
    report.finishedAt = stamp()
    fs.mkdirSync(TMP_DIR, { recursive: true })
    const out = path.join(ROOT_DIR, 'tmp', 'phase2cd_report.json')
    fs.writeFileSync(out, JSON.stringify(report, null, 2))
    console.log(`[phase2cd] wrote ${out}`)
    console.log(`[phase2cd] ${report.result}`)
    for (const [name, value] of Object.entries(report.tests || {})) {
      console.log(`${name}=${value.result}`)
    }
    await closeBrowser().catch(() => {})
    await closePool().catch(() => {})
  }
}

main()
  .then(() => process.exit(0))
  .catch(() => process.exit(1))
