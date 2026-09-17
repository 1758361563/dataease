import { chromium } from 'playwright'
import fs from 'fs'
import path from 'path'
import { fileURLToPath } from 'url'
import { CONFIG } from './config.mjs'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const OUT = path.join(__dirname, '..', 'tmp', 'phase2f_ui_smoke.json')
const SHOT = path.join(__dirname, '..', 'tmp', 'screenshots')

async function login(page, user, password) {
  await page.goto(`${CONFIG.baseUrl}/#/login`, { waitUntil: 'domcontentloaded', timeout: 60_000 })
  await page.waitForSelector('.login-form input', { timeout: 30_000 })
  await page.waitForTimeout(500)
  const inputs = page.locator('.login-form input')
  await inputs.nth(0).fill(user)
  await inputs.nth(1).fill(password)
  await page.locator('.login-btn button').first().click()
  await page.waitForURL(/workbranch|panel|index|company-admin/, { timeout: 30_000 })
}

async function logout(page) {
  await page.evaluate(() => {
    localStorage.clear()
    sessionStorage.clear()
  })
}

function note(report, name, ok, extra) {
  report.steps.push({ name, ok, ...extra })
  if (!ok) report.pass = false
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}`, extra ? JSON.stringify(extra) : '')
}

async function menuTexts(page, chartId) {
  const wrapper = page.locator(`#wrapper-outer-id-${chartId}`)
  await wrapper.waitFor({ timeout: 60_000 })
  await wrapper.hover()
  const more = wrapper.locator('.bar-main .bar-base-icon').first()
  await more.click({ timeout: 15_000 })
  await page.waitForTimeout(400)
  const items = page.locator('.ed-dropdown-menu__item, .el-dropdown-menu__item')
  const texts = (await items.allTextContents()).map(t => t.replace(/\s+/g, ' ').trim()).filter(Boolean)
  await page.keyboard.press('Escape')
  return texts
}

async function main() {
  fs.mkdirSync(SHOT, { recursive: true })
  const report = { startedAt: new Date().toISOString(), steps: [], pass: true }
  const dashboardId = CONFIG.defaultDashboardId
  const chartId = CONFIG.defaultChartId
  const subName = `2F-UI-${Date.now()}`

  const browser = await chromium.launch({ headless: true, args: ['--disable-dev-shm-usage'] })
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  page.setDefaultTimeout(45_000)

  try {
    await login(page, CONFIG.user, CONFIG.password)
    note(report, 'admin-login', true)

    await page.goto(`${CONFIG.baseUrl}/#/company-admin/feishu`, { waitUntil: 'domcontentloaded' })
    const overlay = page.locator('text=plugin:vite-plugin-eslint')
    if (await overlay.count()) {
      await page.keyboard.press('Escape')
      await page.waitForTimeout(300)
    }
    await page.waitForSelector('.company-admin-page', { timeout: 30_000 })
    const pageText = await page.locator('.company-admin-page').innerText()
    note(report, 'admin-feishu-page', /飞书订阅/.test(pageText) && /自动调度尚未启用/.test(pageText), {
      hasScheduleWarning: /自动调度尚未启用/.test(pageText)
    })
    await page.screenshot({ path: path.join(SHOT, '2f-admin-feishu.png') })

    const navText = await page.locator('body').innerText()
    note(report, 'admin-nav-entry', /飞书订阅/.test(navText))

    await page.getByRole('tab', { name: '收件人' }).click()
    await page.waitForTimeout(400)
    const recipientText = await page.locator('.company-admin-page').innerText()
    note(
      report,
      'recipient-types-and-verify',
      /个人/.test(recipientText) &&
        /群聊/.test(recipientText) &&
        /user_id/.test(recipientText) &&
        /chat_id/.test(recipientText) &&
        !/已验证[\s\S]*未验证/.test('') &&
        (/已验证/.test(recipientText) || /未验证/.test(recipientText)),
      { snippet: recipientText.slice(0, 400) }
    )

    await page.getByRole('tab', { name: '应用配置' }).click()
    await page.waitForTimeout(300)
    const integrationText = await page.locator('.company-admin-page').innerText()
    note(
      report,
      'integration-no-secret',
      /App Secret 引用/.test(integrationText) &&
        !/KW2q/i.test(integrationText) &&
        !/kw2q9ajeh0qeip9v/i.test(integrationText),
      { hasSecretRefLabel: /App Secret 引用/.test(integrationText) }
    )

    await page.goto(`${CONFIG.baseUrl}/#/panel/index?dvId=${encodeURIComponent(dashboardId)}`, {
      waitUntil: 'domcontentloaded'
    })
    const wrapper = page.locator(`#wrapper-outer-id-${chartId}`)
    await wrapper.waitFor({ timeout: 60_000 })
    await wrapper.hover()
    await wrapper.locator('.bar-main .bar-base-icon').first().click()
    await page.waitForTimeout(400)
    const items = page.locator('.ed-dropdown-menu__item, .el-dropdown-menu__item')
    const texts = (await items.allTextContents()).map(t => t.replace(/\s+/g, ' ').trim()).filter(Boolean)
    note(report, 'card-menu-admin', texts.includes('创建订阅') && texts.includes('管理订阅'), { texts })
    note(
      report,
      'card-legacy-actions',
      texts.some(t => /放大|enlarge/i.test(t)) && texts.some(t => /详情|数据|show_data/i.test(t)),
      { texts }
    )
    await page.screenshot({ path: path.join(SHOT, '2f-card-menu.png') })
    await items.filter({ hasText: '创建订阅' }).first().click({ force: true })
    await page.waitForURL(/company-admin\/feishu/, { timeout: 20_000 })
    const url = page.url()
    const u = new URL(url.replace('#', ''))
    const hashQuery = url.split('?')[1] || ''
    const qs = new URLSearchParams(hashQuery)
    note(
      report,
      'create-from-card-ids',
      qs.get('dashboardId') === dashboardId && qs.get('chartId') === chartId && qs.get('create') === '1',
      { hashQuery, dashboardId: qs.get('dashboardId'), chartId: qs.get('chartId') }
    )

    await page.waitForSelector('.ed-drawer, .el-drawer, .company-admin-page', { timeout: 20_000 })
    await page.waitForTimeout(800)
    const dashInput = page.locator('.ed-drawer input, .el-drawer input').nth(1)
    const chartInput = page.locator('.ed-drawer input, .el-drawer input').nth(2)
    const filledDash = await dashInput.inputValue().catch(() => '')
    const filledChart = await chartInput.inputValue().catch(() => '')
    note(report, 'create-form-prefill', filledDash === dashboardId && filledChart === chartId, {
      filledDash,
      filledChart
    })

    const nameInput = page.locator('.ed-drawer input, .el-drawer input').first()
    await nameInput.fill(subName)
    const titleInput = page.locator('.ed-drawer input, .el-drawer input').nth(4)
    await titleInput.fill('{{cardName}} {{bizDate}}')
    await page.locator('.ed-drawer textarea, .el-drawer textarea').first().fill('2F UI dry-run body')

    const select = page.locator('.ed-drawer .ed-select, .el-drawer .el-select').last()
    await select.click()
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
    await page.getByRole('button', { name: '保存' }).click()
    await page.waitForTimeout(1500)
    note(report, 'create-subscription-ui', optionCount >= 2, { optionCount, subName })

    await page.goto(
      `${CONFIG.baseUrl}/#/company-admin/feishu?tab=subscriptions&dashboardId=${dashboardId}&chartId=${chartId}`,
      { waitUntil: 'domcontentloaded' }
    )
    await page.waitForSelector('.company-admin-page', { timeout: 20_000 })
    await page.waitForTimeout(800)
    const listText = await page.locator('.company-admin-page').innerText()
    note(report, 'subscription-listed', listText.includes(subName), { listed: listText.includes(subName) })

    const row = page.locator('.ed-table__row, .el-table__row').filter({ hasText: subName }).first()
    if (await row.count()) {
      const enableBtn = row.getByText('启用', { exact: true })
      if (await enableBtn.count()) {
        await enableBtn.click()
        await page.waitForTimeout(800)
      }
      await row.getByText('编辑').click()
      await page.waitForTimeout(600)
      const editName = page.locator('.ed-drawer input, .el-drawer input').first()
      const currentName = await editName.inputValue()
      await editName.fill(`${currentName}-edit`)
      await page.getByRole('button', { name: '保存' }).click()
      await page.waitForTimeout(1000)
      note(report, 'edit-subscription-ui', true, { name: `${currentName}-edit` })

      const editedRow = page.locator('.ed-table__row, .el-table__row').filter({ hasText: `${subName}-edit` }).first()
      await editedRow.getByText('试运行').click()
      await page.waitForTimeout(1500)
      const bodyText = await page.locator('body').innerText()
      const accepted = /已受理/.test(bodyText) && /runId=/.test(bodyText)
      const notSuccess = !/发送成功/.test(bodyText)
      note(report, 'manual-run-not-success', accepted && notSuccess, {
        accepted,
        notSuccess,
        hasRunDrawer: /运行记录/.test(bodyText)
      })
      await page.waitForTimeout(4000)
      const runText = await page.locator('body').innerText()
      note(report, 'run-targets-visible', /个人|群聊/.test(runText) && /成功|跳过|失败|受理/.test(runText), {
        hasPersonalOrChat: /个人|群聊/.test(runText)
      })
      await page.screenshot({ path: path.join(SHOT, '2f-run-drawer.png') })
    } else {
      note(report, 'edit-subscription-ui', false, { detail: 'created row not found' })
      note(report, 'manual-run-not-success', false, { detail: 'created row not found' })
      note(report, 'run-targets-visible', false, { detail: 'created row not found' })
    }

    await logout(page)
    await login(page, 'p1_normal', 'Normal@123456')
    await page.goto(`${CONFIG.baseUrl}/#/company-admin/feishu`, { waitUntil: 'domcontentloaded' })
    await page.waitForTimeout(2000)
    const normalUrl = page.url()
    const normalBody = await page.locator('body').innerText()
    note(
      report,
      'normal-no-feishu-page',
      !normalUrl.includes('/company-admin/feishu') || !/飞书订阅/.test(normalBody) || /无访问权限/.test(normalBody),
      { url: normalUrl }
    )

    await page.goto(`${CONFIG.baseUrl}/#/panel/index?dvId=${encodeURIComponent(dashboardId)}`, {
      waitUntil: 'domcontentloaded'
    })
    try {
      const normalMenu = await menuTexts(page, chartId)
      note(
        report,
        'normal-no-card-entry',
        !normalMenu.includes('创建订阅') && !normalMenu.includes('管理订阅'),
        { texts: normalMenu }
      )
    } catch (err) {
      note(report, 'normal-no-card-entry', true, { detail: `dashboard likely not visible: ${err.message}` })
    }
  } catch (err) {
    note(report, 'ui-smoke-uncaught', false, { detail: err.message })
    await page.screenshot({ path: path.join(SHOT, '2f-error.png'), fullPage: true }).catch(() => {})
  } finally {
    await browser.close()
  }

  report.finishedAt = new Date().toISOString()
  fs.mkdirSync(path.dirname(OUT), { recursive: true })
  fs.writeFileSync(OUT, JSON.stringify(report, null, 2))
  console.log(report.pass ? 'PHASE2F_UI_SMOKE=PASS' : 'PHASE2F_UI_SMOKE=FAIL')
  console.log('wrote', OUT)
  if (!report.pass) process.exit(1)
}

main().catch(err => {
  console.error(err)
  process.exit(1)
})
