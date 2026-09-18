import { chromium } from 'playwright'
import fs from 'fs'
import path from 'path'
import { fileURLToPath } from 'url'
import { CONFIG } from './config.mjs'
import { query, closePool } from './db.mjs'
import { shanghaiWall, shanghaiWallToMs, currentDueSlot, parseSchedule } from './schedule.mjs'
import { ensureExecutionModeSchema } from './store.mjs'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const OUT = path.join(__dirname, '..', 'tmp', 'phase2e_clock_ui.json')

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
  if (!pushed?.ok) {
    throw new Error(`vue-router push failed: ${pushed?.reason || 'unknown'}`)
  }
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

async function waitForScheduledRun(subId, timeoutMs) {
  const started = Date.now()
  while (Date.now() - started < timeoutMs) {
    const rows = await query(
      `SELECT id, status, execution_mode, error_summary, planned_at, trigger_type
         FROM company_feishu_delivery_run
        WHERE subscription_id = ? AND trigger_type = 'SCHEDULED'`,
      [subId]
    )
    const done = rows.filter(row => ['SUCCEEDED', 'SKIPPED', 'FAILED'].includes(String(row.status)))
    if (done.length) return rows
    await sleep(5000)
  }
  return query(
    `SELECT id, status, execution_mode, error_summary, planned_at, trigger_type
       FROM company_feishu_delivery_run
      WHERE subscription_id = ? AND trigger_type = 'SCHEDULED'`,
    [subId]
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

async function main() {
  assertDryRun()
  await ensureExecutionModeSchema()
  const report = {
    startedAt: new Date().toISOString(),
    steps: [],
    pass: true,
    dryRun: CONFIG.dryRun
  }
  const subName = `[2E-CLOCK] ${Date.now()}`
  const now = Date.now()
  const fireAt = now + 2 * 60 * 1000
  const wall = shanghaiWall(fireAt)
  const time = `${pad(wall.hour)}:${pad(wall.minute)}`
  const expectedSlot = shanghaiWallToMs(wall.year, wall.month, wall.day, wall.hour, wall.minute)
  report.scheduleTime = time
  report.expectedSlot = expectedSlot

  const browser = await chromium.launch({ headless: true, args: ['--disable-dev-shm-usage'] })
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  page.setDefaultTimeout(45_000)
  let subId = null

  try {
    await login(page, CONFIG.user, CONFIG.password)
    note(report, 'admin-login', true)
    try {
      await openFeishuAdmin(page)
    } catch (err) {
      report.pageUrl = page.url()
      report.pageText = (await page.locator('body').innerText().catch(() => '')).slice(0, 1500)
      report.menuItems = await page.locator('.ed-menu-item, .el-menu-item').allTextContents().catch(() => [])
      throw err
    }
    await page.getByRole('button', { name: '新建订阅' }).click()
    const createDrawer = page.locator('.ed-drawer, .el-drawer').filter({ hasText: '创建订阅' }).last()
    await createDrawer.waitFor({ timeout: 20_000 })
    await page.waitForTimeout(600)

    await createDrawer.locator('input').first().fill(subName)
    await createDrawer.locator('input').nth(1).fill(CONFIG.defaultDashboardId)
    await createDrawer.locator('input').nth(2).fill(CONFIG.defaultChartId)

    const drawer = createDrawer
    await drawer.locator('.ed-form-item, .el-form-item').filter({ hasText: /^调度/ }).locator('.ed-select, .el-select').click()
    await page.waitForTimeout(300)
    await page.locator('.ed-select-dropdown__item, .el-select-dropdown__item').filter({ hasText: '每天' }).click()
    await page.waitForTimeout(300)
    await drawer.getByPlaceholder('HH:mm').fill(time)
    const autoSwitch = drawer.locator('.ed-switch, .el-switch').last()
    const checked = await autoSwitch.getAttribute('class')
    if (!/is-checked/.test(String(checked || ''))) {
      await autoSwitch.click()
    }

    const recipientSelect = drawer.locator('.ed-form-item, .el-form-item').filter({ hasText: '收件人' }).locator('.ed-select, .el-select')
    await recipientSelect.click()
    await page.waitForTimeout(400)
    const options = page.locator('.ed-select-dropdown__item, .el-select-dropdown__item')
    const optionCount = await options.count()
    if (optionCount >= 2) {
      await options.nth(0).click()
      await options.nth(1).click()
    } else if (optionCount === 1) {
      await options.nth(0).click()
    }
    await page.keyboard.press('Escape')
    await drawer.getByRole('button', { name: '保存' }).click()
    await page.waitForTimeout(1500)
    note(report, 'create-subscription-ui', optionCount >= 2, { optionCount, subName, time })

    await page.getByRole('button', { name: '刷新' }).click()
    await page.waitForTimeout(800)
    const row = page.locator('.ed-table__row, .el-table__row').filter({ hasText: subName }).first()
    note(report, 'subscription-listed', (await row.count()) > 0)
    const enableBtn = row.getByText('启用', { exact: true })
    if (await enableBtn.count()) {
      await enableBtn.click()
      await page.waitForTimeout(1000)
    }

    const dbSub = await query(
      `SELECT id, status, schedule_json FROM company_feishu_subscription WHERE name = ? ORDER BY id DESC LIMIT 1`,
      [subName]
    )
    note(report, 'page-schedule-persisted', dbSub.length === 1, { row: dbSub[0] })
    if (!dbSub.length) throw new Error('subscription not found in database')
    subId = dbSub[0].id
    const parsed = parseSchedule(dbSub[0].schedule_json)
    note(
      report,
      'backend-reads-ui-schedule',
      parsed.kind === 'daily' && parsed.autoRun === true && parsed.time === time && parsed.timezone === 'Asia/Shanghai',
      { parsed, status: dbSub[0].status }
    )
    if (String(dbSub[0].status) !== 'ENABLED') {
      throw new Error(`subscription status ${dbSub[0].status}, expected ENABLED`)
    }
    const dueNow = currentDueSlot(Date.now(), parsed)
    note(report, 'not-due-before-slot', dueNow === null || dueNow === expectedSlot, { dueNow, expectedSlot, now: Date.now() })

    const waitMs = Math.max(expectedSlot + 70_000 - Date.now(), 15_000)
    report.waitMs = waitMs
    const runs = await waitForScheduledRun(subId, waitMs)
    note(report, 'scheduler-created-run', runs.length >= 1, {
      count: runs.length,
      runs: runs.map(row => ({
        id: String(row.id),
        status: row.status,
        execution_mode: row.execution_mode,
        planned_at: String(row.planned_at)
      }))
    })
    if (!runs.length) throw new Error('scheduler did not create a run')
    note(report, 'single-slot-run', runs.length === 1 && String(runs[0].planned_at) === String(expectedSlot), {
      plannedAt: String(runs[0].planned_at),
      expectedSlot: String(expectedSlot)
    })
    note(report, 'stored-dry-run', String(runs[0].execution_mode || '').toUpperCase() === 'DRY_RUN', {
      execution_mode: runs[0].execution_mode,
      error_summary: runs[0].error_summary
    })

    await page.getByRole('button', { name: '刷新' }).click()
    await page.waitForTimeout(600)
    const listed = page.locator('.ed-table__row, .el-table__row').filter({ hasText: subName }).first()
    await listed.getByText('运行记录').click()
    await page.waitForTimeout(1500)
    const runDrawer = page.locator('.ed-drawer, .el-drawer').filter({ hasText: '运行记录' }).last()
    await runDrawer.waitFor({ timeout: 15_000 })
    const drawerText = await runDrawer.innerText()
    note(
      report,
      'page-shows-simulated-success',
      /模拟执行成功/.test(drawerText) && !/真实发送成功/.test(drawerText),
      { snippet: drawerText.slice(0, 500) }
    )
    await runDrawer.getByText('展开').first().click()
    await page.waitForTimeout(1000)
    const detailText = await runDrawer.innerText()
    const simulatedCount = (detailText.match(/模拟执行成功/g) || []).length
    note(
      report,
      'two-recipient-simulated',
      simulatedCount >= 2 && !/om_/.test(detailText),
      { simulatedCount, snippet: detailText.slice(0, 800) }
    )
    await page.keyboard.press('Escape')
    await page.waitForTimeout(400)

    await sleep(35_000)
    const later = await query(
      `SELECT id FROM company_feishu_delivery_run WHERE subscription_id = ? AND trigger_type = 'SCHEDULED'`,
      [subId]
    )
    note(report, 'no-duplicate-slot', later.length === 1, { count: later.length })
  } catch (err) {
    report.pass = false
    report.error = err.message
    console.error('[phase2e-clock-ui] FAIL', err.message)
  } finally {
    try {
      if (page && subName) {
        await openFeishuAdmin(page)
        const disabled = await disableSubscription(page, subName)
        note(report, 'disable-test-subscription-ui', disabled, { disabled, subName })
      }
    } catch (disableErr) {
      report.disableError = disableErr.message
      note(report, 'disable-test-subscription-ui', false, { error: disableErr.message })
    }
    if (subId) {
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
    }
    await browser.close().catch(() => {})
    await closePool().catch(() => {})
    report.finishedAt = new Date().toISOString()
    fs.mkdirSync(path.dirname(OUT), { recursive: true })
    fs.writeFileSync(OUT, JSON.stringify(report, null, 2))
    console.log(`[phase2e-clock-ui] ${report.pass ? 'PASS' : 'FAIL'}`)
    if (!report.pass) process.exitCode = 1
  }
}

function assertDryRun() {
  if (CONFIG.dryRun !== true) {
    throw new Error('Refusing clock test because CONFIG.dryRun is not true')
  }
}

main()
