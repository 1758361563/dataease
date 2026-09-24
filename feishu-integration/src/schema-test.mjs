import fs from 'fs'
import path from 'path'
import { fileURLToPath } from 'url'
import { CONFIG, ROOT_DIR } from './config.mjs'
import { closePool, exec, query } from './db.mjs'
import {
  addSubscriptionRecipient,
  deleteTestRows,
  ensureDeliveryLaneSchema,
  ensureExecutionModeSchema,
  ensureIdentityStatusSchema,
  insertAppConfig,
  insertDeliveryRun,
  insertDeliveryTarget,
  insertIdentityMap,
  insertRecipient,
  insertSubscription,
  listFailedTargets,
  listSubscriptionRecipients,
  updateDeliveryRun
} from './store.mjs'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const SCHEMA_DIR = [
  path.resolve(ROOT_DIR, '../core/core-backend/src/main/resources/db/company'),
  path.resolve(ROOT_DIR, '../dataease/core/core-backend/src/main/resources/db/company')
].find(candidate => fs.existsSync(candidate))
if (!SCHEMA_DIR) {
  throw new Error('company_feishu SQL directory not found relative to feishu-integration/')
}
const SCHEMA_FILES = [
  'company_feishu_app_config.sql',
  'company_feishu_recipient.sql',
  'company_feishu_identity_map.sql',
  'company_feishu_subscription.sql',
  'company_feishu_subscription_recipient.sql',
  'company_feishu_delivery_run.sql',
  'company_feishu_delivery_target.sql',
  'company_feishu_source.sql'
]

function assert(cond, msg) {
  if (!cond) throw new Error(msg)
}

async function applySchema() {
  for (const file of SCHEMA_FILES) {
    const sql = fs.readFileSync(path.join(SCHEMA_DIR, file), 'utf8')
    await exec(sql)
  }
}

async function cleanupByAppId(appId) {
  const recips = await query(`SELECT id FROM company_feishu_recipient WHERE app_id = ?`, [appId])
  const subs = await query(`SELECT id FROM company_feishu_subscription WHERE name = 'phase2a-card'`)
  const ids = [
    ...recips.map(row => row.id),
    ...subs.map(row => row.id)
  ]
  if (ids.length) await deleteTestRows(ids)
  await exec(`DELETE FROM company_feishu_app_config WHERE app_id = ?`, [appId])
  await exec(`DELETE FROM company_feishu_identity_map WHERE app_id = ?`, [appId])
}

async function expectDuplicate(fn) {
  try {
    await fn()
    throw new Error('expected duplicate key error')
  } catch (err) {
    const code = err.code || err.errno
    if (code !== 'ER_DUP_ENTRY' && err.errno !== 1062) throw err
  }
}

async function main() {
  const ids = []
  const report = { schema: [], crud: {}, constraints: {}, leftover: null }
  try {
    await applySchema()
    await ensureExecutionModeSchema()
    await ensureIdentityStatusSchema()
    await ensureDeliveryLaneSchema()
    await cleanupByAppId('cli_phase2a_test')
    const tables = await query(
      `SELECT table_name FROM information_schema.tables
        WHERE table_schema = ? AND table_name LIKE 'company_feishu_%'
        ORDER BY table_name`,
      [CONFIG.mysql.database]
    )
    report.schema = tables.map(row => row.TABLE_NAME || row.table_name)
    assert(report.schema.length === 7, `expected 7 company_feishu_* tables, got ${report.schema.join(',')}`)
    const modeCols = await query(
      `SELECT COLUMN_NAME AS column_name FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'company_feishu_delivery_run' AND COLUMN_NAME = 'execution_mode'`,
      [CONFIG.mysql.database]
    )
    assert(modeCols.length === 1, 'execution_mode column missing after compat migration')
    const identityCols = await query(
      `SELECT COLUMN_NAME AS column_name FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'company_feishu_recipient' AND COLUMN_NAME = 'identity_status'`,
      [CONFIG.mysql.database]
    )
    assert(identityCols.length === 1, 'identity_status column missing after compat migration')
    const laneCols = await query(
      `SELECT COLUMN_NAME AS column_name FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'company_feishu_subscription' AND COLUMN_NAME = 'delivery_lane'`,
      [CONFIG.mysql.database]
    )
    assert(laneCols.length === 1, 'delivery_lane column missing after compat migration')

    const appConfigId = await insertAppConfig({
      appId: 'cli_phase2a_test',
      appSecretRef: 'FEISHU_APP_SECRET',
      createdBy: 1
    })
    ids.push(appConfigId)

    const personId = await insertRecipient({
      recipientType: 'PERSONAL',
      receiveIdType: 'user_id',
      displayName: 'phase2a-user',
      feishuId: 'testid_user_2a',
      appId: 'cli_phase2a_test',
      source: 'GUANDATA_FEISHU_ACCOUNT',
      verifyStatus: 'UNVERIFIED',
      createdBy: 1
    })
    const chatId = await insertRecipient({
      recipientType: 'CHAT',
      receiveIdType: 'chat_id',
      displayName: 'phase2a-chat',
      feishuId: 'oc_phase2a_test_chat',
      appId: 'cli_phase2a_test',
      source: 'MANUAL',
      verifyStatus: 'VERIFIED',
      createdBy: 1
    })
    ids.push(personId, chatId)

    const mapId = await insertIdentityMap({
      biUserId: 1,
      recipientId: personId,
      appId: 'cli_phase2a_test',
      source: 'GUANDATA_FEISHU_ACCOUNT',
      createdBy: 1
    })
    ids.push(mapId)

    const subId = await insertSubscription({
      name: 'phase2a-card',
      dashboardId: '1292912367851671552',
      chartId: '7501169547546726400',
      chartName: '全渠道系列商品排行表',
      fixedFilterJson: JSON.stringify({
        version: 1,
        items: [{ queryFieldId: '7501179661171298304', name: '系列', operator: 'in', value: ['凉白开'] }]
      }),
      scheduleJson: JSON.stringify({ kind: 'daily', time: '09:00', timezone: 'Asia/Shanghai' }),
      status: 'ENABLED',
      createdBy: 1
    })
    ids.push(subId)
    await addSubscriptionRecipient(subId, personId, 1)
    await addSubscriptionRecipient(subId, chatId, 1)

    const linked = await listSubscriptionRecipients(subId)
    report.crud.recipientCount = linked.length
    report.crud.receiveIdTypes = linked.map(row => row.receive_id_type).sort()
    assert(linked.length === 2, 'subscription should have 2 recipients')
    assert(report.crud.receiveIdTypes.join(',') === 'chat_id,user_id', 'expected chat_id and user_id')

    const plannedAt = Date.now()
    const runId = await insertDeliveryRun({
      subscriptionId: subId,
      triggerType: 'MANUAL',
      plannedAt,
      operatorUserId: 1,
      status: 'RUNNING',
      screenshotStatus: 'FAILED',
      screenshotError: 'tiny-font-not-a-send-failure',
      startedAt: plannedAt
    })
    ids.push(runId)
    await updateDeliveryRun(runId, {
      screenshotStatus: 'SUCCEEDED',
      screenshotError: null,
      screenshotAt: Date.now(),
      pngRef: 'tmp/phase2a.png',
      uploadStatus: 'SUCCEEDED',
      uploadAt: Date.now(),
      imageKey: 'img_phase2a_test'
    })
    await insertDeliveryTarget({
      runId,
      recipientId: chatId,
      receiveIdType: 'chat_id',
      feishuId: 'oc_phase2a_test_chat',
      feishuMessageId: 'om_phase2a_ok',
      status: 'SUCCEEDED',
      sentAt: Date.now()
    })
    await insertDeliveryTarget({
      runId,
      recipientId: personId,
      receiveIdType: 'user_id',
      feishuId: 'testid_user_2a',
      status: 'FAILED',
      errorSummary: 'bot cannot reach user'
    })
    await updateDeliveryRun(runId, { status: 'FAILED', errorSummary: '1/2 targets failed', finishedAt: Date.now() })

    const failed = await listFailedTargets(runId)
    report.crud.failedTargets = failed.map(row => ({ name: row.display_name, status: row.status }))
    assert(failed.length === 1, 'exactly one failed target')
    assert(failed[0].receive_id_type === 'user_id', 'failed target should be personal user_id')

    await expectDuplicate(() =>
      insertRecipient({
        recipientType: 'PERSONAL',
        receiveIdType: 'user_id',
        displayName: 'dup',
        feishuId: 'testid_user_2a',
        appId: 'cli_phase2a_test'
      })
    )
    await expectDuplicate(() =>
      insertDeliveryRun({
        subscriptionId: subId,
        triggerType: 'MANUAL',
        plannedAt,
        operatorUserId: 1,
        status: 'PENDING'
      })
    )
    report.constraints.duplicateRecipient = 'PASS'
    report.constraints.duplicateRun = 'PASS'
    report.result = 'PASS'
  } catch (err) {
    report.result = 'FAIL'
    report.error = err.message
    throw err
  } finally {
    try {
      await deleteTestRows(ids)
      const leftover = await query(
        `SELECT COUNT(*) AS c FROM company_feishu_recipient WHERE app_id = 'cli_phase2a_test'`
      )
      report.leftover = Number(leftover[0].c || leftover[0].C || 0)
    } catch (cleanupErr) {
      report.cleanupError = cleanupErr.message
    }
    await closePool()
    const out = path.join(ROOT_DIR, 'tmp', 'phase2a_schema_test.json')
    fs.mkdirSync(path.dirname(out), { recursive: true })
    fs.writeFileSync(out, JSON.stringify(report, null, 2))
    console.log(`[phase2a] ${report.result}`)
    console.log(JSON.stringify(report, null, 2))
  }
}

main().then(() => process.exit(0)).catch(() => process.exit(1))
