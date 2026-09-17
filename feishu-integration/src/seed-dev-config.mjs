import fs from 'fs'
import path from 'path'
import { fileURLToPath } from 'url'
import { CONFIG, ROOT_DIR, TMP_DIR } from './config.mjs'
import { closePool, query } from './db.mjs'
import {
  addSubscriptionRecipient,
  findRecipient,
  findSubscriptionByName,
  getAppConfigByAppId,
  getIdentityMapByRecipient,
  insertAppConfig,
  insertIdentityMap,
  insertRecipient,
  insertSubscription,
  listSubscriptionRecipients
} from './store.mjs'

export const DEV_MARKER = '[DEV]'
export const DEV_SUBSCRIPTION_NAME = '[DEV] 全渠道系列商品排行表-手动测试'
export const DEV_PERSONAL_NAME = '[DEV] Leo 个人收件人（发送已验证，非正式）'
export const DEV_CHAT_NAME = '[DEV] 飞书测试群（发送已验证，非正式）'
export const APP_SECRET_REF = 'FEISHU_APP_SECRET'

function stamp() {
  return new Date().toISOString()
}

function requireDevEnv() {
  const missing = []
  if (!CONFIG.feishuAppId) missing.push('FEISHU_APP_ID')
  if (!CONFIG.feishuTestUserId) missing.push('FEISHU_TEST_USER_ID')
  if (!CONFIG.feishuTestChatId) missing.push('FEISHU_TEST_CHAT_ID')
  if (missing.length) throw new Error(`Missing env: ${missing.join(', ')}`)
}

async function findAdminUser() {
  const rows = await query(
    `SELECT id, username, display_name
       FROM company_bi_user
      WHERE id = 1 OR username IN ('admin', 'leo')
      ORDER BY id
      LIMIT 5`
  )
  return rows
}

export async function seedDevConfig() {
  console.warn('[phase2c] seed-dev-config is a one-off test tool; official config is written by DataEase BFF')
  requireDevEnv()
  const appId = CONFIG.feishuAppId
  const verifiedAt = Date.now()
  const report = {
    startedAt: stamp(),
    dataClass: 'DEV',
    note: 'Phase 2C 开发测试配置。非正式订阅，禁止自动执行。',
    appSecretStored: false,
    appSecretRef: APP_SECRET_REF,
    identity: {
      sendVerifiedPersonal: true,
      sendVerifiedChat: true,
      identityMapVerified: false,
      identityMapScope: 'leo-self-only'
    }
  }

  let app = await getAppConfigByAppId(appId)
  if (!app) {
    const id = await insertAppConfig({
      appId,
      appSecretRef: APP_SECRET_REF,
      status: 'ENABLED',
      lastVerifiedAt: verifiedAt,
      createdBy: 1
    })
    app = await getAppConfigByAppId(appId)
    report.appConfig = { id: String(id), created: true }
  } else {
    report.appConfig = { id: String(app.id), created: false, appSecretRef: app.app_secret_ref }
  }

  let personal = await findRecipient(appId, 'user_id', CONFIG.feishuTestUserId)
  if (!personal) {
    const id = await insertRecipient({
      recipientType: 'PERSONAL',
      receiveIdType: 'user_id',
      displayName: DEV_PERSONAL_NAME,
      feishuId: CONFIG.feishuTestUserId,
      appId,
      source: 'MANUAL',
      verifyStatus: 'VERIFIED',
      lastVerifiedAt: verifiedAt,
      createdBy: 1
    })
    personal = await findRecipient(appId, 'user_id', CONFIG.feishuTestUserId)
    report.personal = { id: String(id), created: true, verifyMeaning: 'recipient-send-verified' }
  } else {
    report.personal = { id: String(personal.id), created: false, verifyMeaning: 'recipient-send-verified' }
  }

  let chat = await findRecipient(appId, 'chat_id', CONFIG.feishuTestChatId)
  if (!chat) {
    const id = await insertRecipient({
      recipientType: 'CHAT',
      receiveIdType: 'chat_id',
      displayName: DEV_CHAT_NAME,
      feishuId: CONFIG.feishuTestChatId,
      appId,
      source: 'MANUAL',
      verifyStatus: 'VERIFIED',
      lastVerifiedAt: verifiedAt,
      createdBy: 1
    })
    chat = await findRecipient(appId, 'chat_id', CONFIG.feishuTestChatId)
    report.chat = { id: String(id), created: true, verifyMeaning: 'recipient-send-verified' }
  } else {
    report.chat = { id: String(chat.id), created: false, verifyMeaning: 'recipient-send-verified' }
  }

  const biUsers = await findAdminUser()
  report.biUsersSeen = biUsers.map(row => ({
    id: String(row.id),
    username: row.username,
    displayName: row.display_name
  }))
  const leoUser = biUsers.find(row => String(row.id) === '1') || null
  if (leoUser) {
    const existingMap = await getIdentityMapByRecipient(personal.id)
    if (!existingMap) {
      const mapId = await insertIdentityMap({
        biUserId: leoUser.id,
        recipientId: personal.id,
        appId,
        source: 'MANUAL',
        createdBy: 1
      })
      report.identity.identityMapId = String(mapId)
      report.identity.identityMapCreated = true
    } else {
      report.identity.identityMapId = String(existingMap.id)
      report.identity.identityMapCreated = false
    }
    report.identity.identityMapVerified = true
    report.identity.biUserId = String(leoUser.id)
    report.identity.note =
      '仅 Leo 本人完成 BI 用户与飞书个人核对；不得据此把其他观远用户标为已映射。'
  } else {
    report.identity.note = '未找到 company_bi_user id=1，跳过 identity_map。收件人发送验证仍然成立。'
  }

  let sub = await findSubscriptionByName(DEV_SUBSCRIPTION_NAME)
  if (!sub) {
    const id = await insertSubscription({
      name: DEV_SUBSCRIPTION_NAME,
      dashboardId: CONFIG.defaultDashboardId,
      chartId: CONFIG.defaultChartId,
      chartName: '全渠道系列商品排行表',
      titleTemplate: '[DEV] 全渠道系列商品排行表',
      bodyTemplate: 'Phase 2C/2D 开发测试订阅，非正式日报，禁止自动执行。',
      includeDetailLink: false,
      fixedFilterJson: JSON.stringify({
        version: 1,
        items: [],
        note: '全渠道默认筛选；不套用 1B 凉白开实验值'
      }),
      scheduleJson: JSON.stringify({
        kind: 'manual',
        autoRun: false,
        timezone: 'Asia/Shanghai',
        env: 'dev'
      }),
      freshnessJson: JSON.stringify({
        env: 'dev',
        dataClass: 'DEV',
        note: '开发测试数据，非正式配置'
      }),
      status: 'ENABLED',
      createdBy: 1
    })
    sub = await findSubscriptionByName(DEV_SUBSCRIPTION_NAME)
    report.subscription = { id: String(id), created: true }
  } else {
    report.subscription = { id: String(sub.id), created: false }
  }

  const linked = await listSubscriptionRecipients(sub.id)
  const linkedIds = new Set(linked.map(row => String(row.id)))
  if (!linkedIds.has(String(personal.id))) {
    await addSubscriptionRecipient(sub.id, personal.id, 1)
  }
  if (!linkedIds.has(String(chat.id))) {
    await addSubscriptionRecipient(sub.id, chat.id, 1)
  }
  const recipients = await listSubscriptionRecipients(sub.id)
  report.subscription.recipients = recipients.map(row => ({
    id: String(row.id),
    type: row.recipient_type,
    receiveIdType: row.receive_id_type,
    verifyStatus: row.verify_status,
    displayName: row.display_name
  }))
  report.autoRun = false
  report.manualTriggerOnly = true
  report.finishedAt = stamp()
  return report
}

async function main() {
  fs.mkdirSync(TMP_DIR, { recursive: true })
  try {
    const report = await seedDevConfig()
    const out = path.join(ROOT_DIR, 'tmp', 'phase2c_seed.json')
    fs.writeFileSync(out, JSON.stringify(report, null, 2))
    console.log(`[phase2c] wrote ${out}`)
    console.log(`subscription_id=${report.subscription.id}`)
    console.log(`personal_id=${report.personal.id}`)
    console.log(`chat_id=${report.chat.id}`)
    console.log(`identity_map_verified=${report.identity.identityMapVerified}`)
  } finally {
    await closePool()
  }
}

const isMain =
  Boolean(process.argv[1]) &&
  path.normalize(fileURLToPath(import.meta.url)) === path.normalize(path.resolve(process.argv[1]))

if (isMain) {
  main()
    .then(() => process.exit(0))
    .catch(err => {
      console.error('[phase2c] ERROR', err.message)
      process.exit(1)
    })
}
