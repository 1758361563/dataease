import { chromium } from 'playwright'
import fs from 'fs'
import path from 'path'
import { fileURLToPath } from 'url'
import { CONFIG } from './config.mjs'
import { query, closePool } from './db.mjs'
import { shanghaiWall, shanghaiWallToMs, parseSchedule } from './schedule.mjs'
import { ensureExecutionModeSchema } from './store.mjs'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const OUT = path.join(__dirname, '..', 'tmp', 'phase2e_live_clock.json')
const ACCEPT_NAME = '[ACCEPT] 新BI飞书功能测试'

function pad(n) {
  return String(n).padStart(2, '0')
}

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

async function waitForScheduledRun(subId, expectedSlot, timeoutMs) {
  const started = Date.now()
  while (Date.now() - started < timeoutMs) {
    const rows = await query(
      `SELECT id, status, execution_mode, error_summary, planned_at, trigger_type, screenshot_status, upload_status
         FROM company_feishu_delivery_run
        WHERE subscription_id = ? AND trigger_type = 'SCHEDULED' AND planned_at = ?`,
      [subId, expectedSlot]
    )
    const done = rows.filter(row => ['SUCCEEDED', 'SKIPPED', 'FAILED'].includes(String(row.status)))
    if (done.length) return rows
    await sleep(5000)
  }
  return query(
    `SELECT id, status, execution_mode, error_summary, planned_at, trigger_type, screenshot_status, upload_status
       FROM company_feishu_delivery_run
      WHERE subscription_id = ? AND trigger_type = 'SCHEDULED' AND planned_at = ?`,
    [subId, expectedSlot]
  )
}

async function disableSubscription(page, name) {
  const row = page.locator('.ed-table__row, .el-table__row').filter({ hasText: name }).first()
  if (!(await row.count())) return false
  const disableBtn = row.getByText('停用', { exact: true })
  if (await disableBtn.count()) {
    await page.keyboard.press('Escape')
    await page.waitForTimeout(300)
    await disableBtn.click({ force: true })
    await page.waitForTimeout(800)
    return true
  }
  return false
}

async function assertSafeToGoLive() {
  if (CONFIG.dryRun !== false) {
    throw new Error('Refusing live clock test because CONFIG.dryRun is not false')
  }
  const enabled = await query(
    `SELECT id, name, status, schedule_json FROM company_feishu_subscription WHERE status='ENABLED'`
  )
  if (enabled.length !== 1 || enabled[0].name !== ACCEPT_NAME) {
    throw new Error(`expected only ${ACCEPT_NAME} enabled, got ${JSON.stringify(enabled)}`)
  }
  const incomplete = await query(
    `SELECT id, status, execution_mode FROM company_feishu_delivery_run WHERE status IN ('PENDING','RUNNING')`
  )
  if (incomplete.length) {
    throw new Error(`incomplete runs would be recovered as LIVE: ${JSON.stringify(incomplete)}`)
  }
  return enabled[0]
}

async function main() {
  await ensureExecutionModeSchema()
  const report = {
    startedAt: new Date().toISOString(),
    steps: [],
    pass: true,
    dryRun: CONFIG.dryRun
  }
  const sub = await assertSafeToGoLive()
  const subId = sub.id
  const now = Date.now()
  const fireAt = now + 4 * 60 * 1000
  const wall = shanghaiWall(fireAt)
  const time = `${pad(wall.hour)}:${pad(wall.minute)}`
  const expectedSlot = shanghaiWallToMs(wall.year, wall.month, wall.day, wall.hour, wall.minute)
  report.scheduleTime = time
  report.expectedSlot = expectedSlot
  report.subId = String(subId)

  const browser = await chromium.launch({ headless: true, args: ['--disable-dev-shm-usage'] })
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  page.setDefaultTimeout(45_000)

  try {
    note(report, 'isolation-before-schedule', true, { name: sub.name, schedule: sub.schedule_json })
    await login(page, CONFIG.user, CONFIG.password)
    note(report, 'admin-login', true)
    await openFeishuAdmin(page)

    const row = page.locator('.ed-table__row, .el-table__row').filter({ hasText: ACCEPT_NAME }).first()
    await row.getByText('编辑').click()
    const drawer = page.locator('.ed-drawer, .el-drawer').filter({ hasText: '编辑订阅' }).last()
    await drawer.waitFor({ timeout: 20_000 })
    await page.waitForTimeout(500)

    await drawer.locator('.ed-form-item, .el-form-item').filter({ hasText: /^调度/ }).locator('.ed-select, .el-select').click()
    const daily = page.locator('.ed-select-dropdown:visible .ed-select-dropdown__item, .el-select-dropdown:visible .el-select-dropdown__item').filter({ hasText: '每天' })
    await daily.first().waitFor({ state: 'visible', timeout: 10_000 })
    await daily.first().click()
    await page.waitForTimeout(300)
    await drawer.getByPlaceholder('HH:mm').fill(time)
    const autoSwitch = drawer.locator('.ed-switch, .el-switch').last()
    const checked = await autoSwitch.getAttribute('class')
    if (!/is-checked/.test(String(checked || ''))) {
      await autoSwitch.click()
    }
    await drawer.getByRole('button', { name: '保存' }).click()
    await page.waitForTimeout(1500)

    const dbSub = await query(`SELECT id, status, schedule_json FROM company_feishu_subscription WHERE id = ?`, [subId])
    const parsed = parseSchedule(dbSub[0]?.schedule_json)
    note(
      report,
      'page-schedule-live-slot',
      parsed.kind === 'daily' && parsed.autoRun === true && parsed.time === time,
      { parsed, status: dbSub[0]?.status }
    )
    if (String(dbSub[0]?.status) !== 'ENABLED') throw new Error('acceptance subscription is not ENABLED')

    const waitMs = Math.max(expectedSlot + 5 * 60 * 1000 - Date.now(), 30_000)
    report.waitMs = waitMs
    const runs = await waitForScheduledRun(subId, expectedSlot, waitMs)
    const latest = runs[0]
    note(report, 'scheduler-created-live-run', !!latest, {
      count: runs.length,
      runs: runs.map(row => ({
        id: String(row.id),
        status: row.status,
        execution_mode: row.execution_mode,
        planned_at: String(row.planned_at),
        screenshot_status: row.screenshot_status,
        upload_status: row.upload_status
      }))
    })
    if (!latest) throw new Error('scheduler did not create a live run')
    note(
      report,
      'stored-live-success',
      String(latest.execution_mode).toUpperCase() === 'LIVE' && String(latest.status) === 'SUCCEEDED',
      latest
    )

    const targets = await query(
      `SELECT t.status, t.feishu_message_id, t.error_summary, r.display_name, r.recipient_type
         FROM company_feishu_delivery_target t
         JOIN company_feishu_recipient r ON r.id = t.recipient_id
        WHERE t.run_id = ?`,
      [latest.id]
    )
    const liveTargets = targets.filter(
      row => row.status === 'SUCCEEDED' && String(row.feishu_message_id || '').startsWith('om_')
    )
    note(report, 'two-recipients-real-message-id', liveTargets.length === 2 && targets.length === 2, targets)

    await page.getByRole('button', { name: '刷新' }).click()
    await page.waitForTimeout(600)
    await row.getByText('运行记录').click()
    const runDrawer = page.locator('.ed-drawer, .el-drawer').filter({ hasText: '运行记录' }).last()
    await runDrawer.waitFor({ timeout: 15_000 })
    await page.waitForTimeout(800)
    if (await runDrawer.getByText('展开').count()) {
      await runDrawer.getByText('展开').first().click()
      await page.waitForTimeout(1000)
    }
    const detailText = await runDrawer.innerText()
    note(
      report,
      'page-shows-real-send-success',
      /真实发送成功/.test(detailText) && (detailText.match(/om_/g) || []).length >= 2,
      { snippet: detailText.slice(0, 1200), runId: String(latest.id) }
    )
    await page.keyboard.press('Escape')

    await sleep(35_000)
    const later = await query(
      `SELECT id FROM company_feishu_delivery_run
        WHERE subscription_id = ? AND trigger_type = 'SCHEDULED' AND planned_at = ?`,
      [subId, expectedSlot]
    )
    note(report, 'no-duplicate-slot', later.length === 1, { count: later.length })
  } catch (err) {
    report.pass = false
    report.error = err.message
    report.pageUrl = page.url()
    report.pageText = (await page.locator('body').innerText().catch(() => '')).slice(0, 1800)
    console.error('[phase2e-live-clock] FAIL', err.message)
  } finally {
    try {
      await openFeishuAdmin(page)
      const disabled = await disableSubscription(page, ACCEPT_NAME)
      note(report, 'disable-test-subscription-ui', disabled)
    } catch (disableErr) {
      note(report, 'disable-test-subscription-ui', false, { error: disableErr.message })
    }
    await query(`UPDATE company_feishu_subscription SET status='DISABLED', updated_at=? WHERE id = ?`, [
      Date.now(),
      subId
    ]).catch(err => {
      report.disableDbError = err.message
    })
    const after = await query(`SELECT status FROM company_feishu_subscription WHERE id = ?`, [subId]).catch(() => [])
    note(report, 'disable-test-subscription-db', String(after[0]?.status || '') === 'DISABLED', {
      status: after[0]?.status
    })
    await browser.close().catch(() => {})
    await closePool().catch(() => {})
    report.finishedAt = new Date().toISOString()
    fs.mkdirSync(path.dirname(OUT), { recursive: true })
    fs.writeFileSync(
      OUT,
      JSON.stringify(report, (_key, value) => (typeof value === 'bigint' ? value.toString() : value), 2)
    )
    console.log(`[phase2e-live-clock] ${report.pass ? 'PASS' : 'FAIL'}`)
    if (!report.pass) process.exitCode = 1
  }
}

main()
