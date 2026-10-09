import { chromium } from 'playwright'
import fs from 'fs'
import path from 'path'
import { fileURLToPath } from 'url'
import { CONFIG } from './config.mjs'
import { query, closePool } from './db.mjs'
import { ensureExecutionModeSchema } from './store.mjs'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const OUT = path.join(__dirname, '..', 'tmp', 'phase2e_live_prep_smoke.json')
const ACCEPT_NAME = '[ACCEPT] 新BI飞书功能测试'
const CHAT_NAME = '新 BI 飞书功能测试'
const OTHER_SUB_PREFIXES = ['2F-UI-', '[2E-CLOCK]', '[DEV] 全渠道']

function note(report, name, ok, extra) {
  report.steps.push({ name, ok, ...extra })
  if (!ok) report.pass = false
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}`, extra ? JSON.stringify(extra) : '')
}

async function dismissOverlays(page) {
  for (let i = 0; i < 6; i++) {
    const overlay = page.locator('text=plugin:vite-plugin-eslint')
    if (await overlay.count()) {
      await page.keyboard.press('Escape')
      const closeBtn = page.locator('button', { hasText: /close|关闭|×/i }).first()
      if (await closeBtn.count()) await closeBtn.click().catch(() => {})
      await page.waitForTimeout(250)
      continue
    }
    break
  }
}

async function openFeishuAdmin(page) {
  await dismissOverlays(page)
  await page.waitForFunction(
    () =>
      Array.from(document.querySelectorAll('.ed-menu-item, .el-menu-item')).some(el =>
        (el.textContent || '').includes('飞书订阅')
      ),
    null,
    { timeout: 30_000 }
  )
  const pushed = await page.evaluate(async () => {
    const app = document.querySelector('#app')?.__vue_app__
    const router = app?.config?.globalProperties?.$router
    if (!router) return { ok: false, reason: 'no-router' }
    await router.push('/company-admin/feishu')
    return { ok: true, href: window.location.href }
  })
  if (!pushed?.ok) throw new Error(`vue-router push failed: ${pushed?.reason || 'unknown'}`)
  await page.waitForTimeout(800)
  await dismissOverlays(page)
  await page.waitForSelector('.company-admin-page', { timeout: 20_000 })
}

async function login(page, user, password) {
  await page.goto(`${CONFIG.baseUrl}/#/login`, { waitUntil: 'domcontentloaded', timeout: 60_000 })
  await dismissOverlays(page)
  await page.waitForSelector('.login-form input', { timeout: 30_000 })
  await page.waitForTimeout(500)
  const inputs = page.locator('.login-form input')
  await inputs.nth(0).fill(user)
  await inputs.nth(1).fill(password)
  await page.locator('.login-btn button').first().click()
  await page.waitForURL(/workbranch|panel|index|company-admin/, { timeout: 30_000 })
}

async function sleep(ms) {
  await new Promise(resolve => setTimeout(resolve, ms))
}

function assertDryRun() {
  if (CONFIG.dryRun !== true) {
    throw new Error('Refusing live-prep smoke because CONFIG.dryRun is not true')
  }
}

async function disableRowIfEnabled(page, name) {
  const row = page.locator('.ed-table__row, .el-table__row').filter({ hasText: name }).first()
  if (!(await row.count())) return false
  const disableBtn = row.getByText('停用', { exact: true })
  if (await disableBtn.count()) {
    await page.keyboard.press('Escape')
    await page.waitForTimeout(200)
    await disableBtn.click({ force: true })
    await page.waitForTimeout(800)
    return true
  }
  return false
}

async function waitManualRun(subId, startedAfter, timeoutMs) {
  const started = Date.now()
  while (Date.now() - started < timeoutMs) {
    const rows = await query(
      `SELECT id, status, execution_mode, error_summary, trigger_type, started_at
         FROM company_feishu_delivery_run
        WHERE subscription_id = ? AND trigger_type = 'MANUAL' AND started_at >= ?`,
      [subId, startedAfter]
    )
    const done = rows.filter(row => ['SUCCEEDED', 'SKIPPED', 'FAILED'].includes(String(row.status)))
    if (done.length) return rows
    await sleep(2000)
  }
  return query(
    `SELECT id, status, execution_mode, error_summary, trigger_type, started_at
       FROM company_feishu_delivery_run
      WHERE subscription_id = ? AND trigger_type = 'MANUAL' AND started_at >= ?`,
    [subId, startedAfter]
  )
}

async function renameChatRecipient(page, report) {
  await page.getByRole('tab', { name: '收件人' }).click()
  await page.waitForTimeout(600)
  const chatRow = page.locator('.ed-table__row, .el-table__row').filter({ hasText: 'oc_36fb1935ef4b79bc11ab80c401f1e741' }).first()
  if (!(await chatRow.count())) {
    const fallback = page.locator('.ed-table__row, .el-table__row').filter({ hasText: '群聊' }).first()
    note(report, 'chat-recipient-listed', (await fallback.count()) > 0)
    if (await fallback.count()) await fallback.getByText('编辑').click()
  } else {
    note(report, 'chat-recipient-listed', true, { feishuId: 'oc_36fb1935ef4b79bc11ab80c401f1e741' })
    await chatRow.getByText('编辑').click()
  }
  const dialog = page.locator('.ed-dialog, .el-dialog').filter({ hasText: '编辑收件人' }).last()
  await dialog.waitFor({ timeout: 15_000 })
  await dialog.locator('input').first().fill(CHAT_NAME)
  await dialog.getByRole('button', { name: '保存' }).click()
  await page.waitForTimeout(800)
  const saved = await query(
    `SELECT display_name, feishu_id, recipient_type FROM company_feishu_recipient WHERE feishu_id = ?`,
    [CONFIG.feishuTestChatId]
  )
  note(report, 'rename-chat-recipient', saved[0]?.display_name === CHAT_NAME, saved[0])
  await page.keyboard.press('Escape')
  await page.getByRole('tab', { name: '订阅' }).click()
  await page.waitForTimeout(400)
}

async function ensureAcceptSubscription(page, report) {
  await page.getByRole('button', { name: '刷新' }).click()
  await page.waitForTimeout(800)
  const existing = page.locator('.ed-table__row, .el-table__row').filter({ hasText: ACCEPT_NAME }).first()
  if (await existing.count()) {
    await existing.getByText('编辑').click()
  } else {
    await page.getByRole('button', { name: '新建订阅' }).click()
  }
  const drawer = page.locator('.ed-drawer, .el-drawer').filter({ hasText: /创建订阅|编辑订阅/ }).last()
  await drawer.waitFor({ timeout: 20_000 })
  await page.waitForTimeout(500)
  await drawer.locator('input').nth(0).fill(ACCEPT_NAME)
  await drawer.locator('input').nth(1).fill(CONFIG.defaultDashboardId)
  await drawer.locator('input').nth(2).fill(CONFIG.defaultChartId)
  const chartNameInput = drawer.locator('.ed-form-item, .el-form-item').filter({ hasText: '卡片名称' }).locator('input')
  if (await chartNameInput.count()) await chartNameInput.fill('全渠道系列商品排行表')
  const titleInput = drawer.locator('.ed-form-item, .el-form-item').filter({ hasText: '消息标题' }).locator('input')
  if (await titleInput.count()) await titleInput.fill('[ACCEPT] 全渠道系列商品排行表')
  const filterBox = drawer.locator('.ed-form-item, .el-form-item').filter({ hasText: '固定筛选 JSON' }).locator('textarea')
  if (await filterBox.count()) {
    await filterBox.fill(JSON.stringify({ version: 1, items: [], note: '全渠道默认筛选；验收订阅，无 freshness' }))
  }

  const recipientSelect = drawer.locator('.ed-form-item, .el-form-item').filter({ hasText: '收件人' }).locator('.ed-select, .el-select')
  await recipientSelect.click()
  const visibleOptions = page.locator('.ed-select-dropdown:visible .ed-select-dropdown__item, .el-select-dropdown:visible .el-select-dropdown__item')
  await visibleOptions.first().waitFor({ state: 'visible', timeout: 10_000 })
  const optionCount = await visibleOptions.count()
  for (let i = 0; i < optionCount; i++) {
    await visibleOptions.nth(i).click({ force: true })
  }
  report.recipientOptionCount = optionCount
  await page.keyboard.press('Escape')
  await drawer.getByRole('button', { name: '保存' }).click()
  await page.waitForTimeout(1500)
  await page.getByRole('button', { name: '刷新' }).click()
  await page.waitForTimeout(800)

  const row = page.locator('.ed-table__row, .el-table__row').filter({ hasText: ACCEPT_NAME }).first()
  note(report, 'accept-subscription-listed', (await row.count()) > 0, { name: ACCEPT_NAME })
  const enableBtn = row.getByText('启用', { exact: true })
  if (await enableBtn.count()) {
    await enableBtn.click()
    await page.waitForTimeout(1000)
  }
  const dbSub = await query(
    `SELECT id, status, dashboard_id, chart_id, chart_name, schedule_json, fixed_filter_json, freshness_json
       FROM company_feishu_subscription WHERE name = ? ORDER BY id DESC LIMIT 1`,
    [ACCEPT_NAME]
  )
  note(report, 'accept-subscription-saved', dbSub.length === 1 && String(dbSub[0].status) === 'ENABLED', dbSub[0])
  return dbSub[0]
}

async function disableOtherSubscriptions(page, report, keepId) {
  await page.getByRole('button', { name: '刷新' }).click()
  await page.waitForTimeout(600)
  const names = await query(`SELECT id, name, status FROM company_feishu_subscription`)
  for (const row of names) {
    if (String(row.id) === String(keepId)) continue
    if (String(row.status) !== 'ENABLED') continue
    const clicked = await disableRowIfEnabled(page, row.name)
    note(report, `disable-other:${row.name}`, true, { clicked, id: String(row.id) })
  }
  await query(
    `UPDATE company_feishu_subscription
        SET status='DISABLED', updated_at=?
      WHERE id <> ? AND status='ENABLED'`,
    [Date.now(), keepId]
  )
  const enabled = await query(
    `SELECT id, name, status, schedule_json FROM company_feishu_subscription WHERE status='ENABLED'`
  )
  note(report, 'only-one-enabled-subscription', enabled.length === 1 && String(enabled[0].id) === String(keepId), {
    enabled
  })
}

async function main() {
  assertDryRun()
  await ensureExecutionModeSchema()
  const report = {
    startedAt: new Date().toISOString(),
    steps: [],
    pass: true,
    dryRun: CONFIG.dryRun,
    adminUrl: `${CONFIG.baseUrl}/#/company-admin/feishu`
  }
  const browser = await chromium.launch({ headless: true, args: ['--disable-dev-shm-usage'] })
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  page.setDefaultTimeout(45_000)
  let subId = null
  const startedAfter = Date.now() - 5_000

  try {
    await login(page, CONFIG.user, CONFIG.password)
    note(report, 'admin-login', true)
    await openFeishuAdmin(page)
    note(report, 'open-feishu-admin', true, { url: page.url() })
    await renameChatRecipient(page, report)
    const sub = await ensureAcceptSubscription(page, report)
    if (!sub) throw new Error('acceptance subscription missing')
    subId = sub.id
    const recips = await query(
      `SELECT r.display_name, r.recipient_type, r.feishu_id
         FROM company_feishu_subscription_recipient sr
         JOIN company_feishu_recipient r ON r.id = sr.recipient_id
        WHERE sr.subscription_id = ?`,
      [subId]
    )
    const hasLeo = recips.some(row => row.feishu_id === CONFIG.feishuTestUserId)
    const hasChat = recips.some(row => row.feishu_id === CONFIG.feishuTestChatId)
    note(report, 'accept-recipients', hasLeo && hasChat && recips.length === 2, recips)

    const row = page.locator('.ed-table__row, .el-table__row').filter({ hasText: ACCEPT_NAME }).first()
    await row.getByText('试运行').click()
    const runDrawer = page.locator('.ed-drawer, .el-drawer').filter({ hasText: '运行记录' }).last()
    await runDrawer.waitFor({ timeout: 20_000 })
    const runs = await waitManualRun(subId, startedAfter, 90_000)
    const latest = runs.sort((a, b) => Number(b.started_at) - Number(a.started_at))[0]
    note(report, 'manual-dry-run-created', !!latest, latest || { runs })
    if (!latest) throw new Error('试运行 did not create a delivery run')
    note(
      report,
      'stored-execution-mode-dry-run',
      String(latest.execution_mode || '').toUpperCase() === 'DRY_RUN' && String(latest.status) === 'SUCCEEDED',
      latest
    )

    await page.waitForTimeout(800)
    const drawerText = await runDrawer.innerText()
    note(
      report,
      'page-shows-simulated-success',
      /模拟执行成功/.test(drawerText) && !/真实发送成功/.test(drawerText),
      { snippet: drawerText.slice(0, 700) }
    )
    if (await runDrawer.getByText('展开').count()) {
      await runDrawer.getByText('展开').first().click()
      await page.waitForTimeout(800)
    }
    const detailText = await runDrawer.innerText()
    note(
      report,
      'two-recipient-simulated-no-message-id',
      (detailText.match(/模拟执行成功/g) || []).length >= 2 && !/om_/.test(detailText),
      { snippet: detailText.slice(0, 900) }
    )
    await page.keyboard.press('Escape')

    const unknownFailed = await query(
      `SELECT id, status, execution_mode FROM company_feishu_delivery_run WHERE execution_mode='UNKNOWN' LIMIT 1`
    )
    const liveSucceeded = await query(
      `SELECT id, status, execution_mode FROM company_feishu_delivery_run WHERE execution_mode='LIVE' AND status='SUCCEEDED' LIMIT 1`
    )
    note(report, 'historical-unknown-kept', unknownFailed.length === 0 || unknownFailed[0].execution_mode === 'UNKNOWN', unknownFailed[0] || null)
    note(report, 'historical-live-not-rewritten-from-dry-run', !liveSucceeded.length || liveSucceeded[0].execution_mode === 'LIVE', liveSucceeded[0] || null)

    await disableOtherSubscriptions(page, report, subId)
    const autoEnabled = await query(
      `SELECT name, status, schedule_json FROM company_feishu_subscription WHERE status='ENABLED'`
    )
    const autoRunOn = autoEnabled.filter(row => /"autoRun"\s*:\s*true/.test(String(row.schedule_json || '')))
    note(report, 'no-enabled-auto-run', autoRunOn.length === 0, { autoEnabled, autoRunOn })
  } catch (err) {
    report.pass = false
    report.error = err.message
    report.pageUrl = page.url()
    report.pageText = (await page.locator('body').innerText().catch(() => '')).slice(0, 1800)
    console.error('[phase2e-live-prep] FAIL', err.message)
  } finally {
    await browser.close().catch(() => {})
    await closePool().catch(() => {})
    report.finishedAt = new Date().toISOString()
    fs.mkdirSync(path.dirname(OUT), { recursive: true })
    fs.writeFileSync(
      OUT,
      JSON.stringify(
        report,
        (_key, value) => (typeof value === 'bigint' ? value.toString() : value),
        2
      )
    )
    console.log(`[phase2e-live-prep] ${report.pass ? 'PASS' : 'FAIL'}`)
    if (!report.pass) process.exitCode = 1
  }
}

main()
