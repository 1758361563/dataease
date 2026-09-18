import { chromium } from 'playwright'
import { CONFIG } from './config.mjs'

async function login(page, user, password) {
  await page.goto(`${CONFIG.baseUrl}/#/login`, { waitUntil: 'domcontentloaded', timeout: 60_000 })
  await page.waitForSelector('.login-form input', { timeout: 30_000 })
  await page.waitForTimeout(500)
  const inputs = page.locator('.login-form input')
  await inputs.nth(0).fill(user)
  await inputs.nth(1).fill(password)
  await page.locator('.login-btn button').first().click()
  await page.waitForURL(/workbranch|panel|index/, { timeout: 30_000 })
}

async function main() {
  const browser = await chromium.launch({ headless: true, args: ['--disable-dev-shm-usage'] })
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  const report = { pass: true, steps: [] }
  const note = (name, ok, extra) => {
    report.steps.push({ name, ok, ...extra })
    if (!ok) report.pass = false
    console.log(`${ok ? 'PASS' : 'FAIL'} ${name}`, extra ? JSON.stringify(extra) : '')
  }
  try {
    await login(page, CONFIG.user, CONFIG.password)
    await page.goto(`${CONFIG.baseUrl}/#/company-admin/feishu`, {
      waitUntil: 'domcontentloaded',
      timeout: 60_000
    })
    const overlay = page.locator('text=plugin:vite-plugin-eslint')
    if (await overlay.count()) {
      await page.keyboard.press('Escape')
      await page.waitForTimeout(300)
    }
    await page.waitForSelector('.company-admin-page', { timeout: 30_000 })
    await page.getByRole('tab', { name: '收件人' }).click()
    await page.waitForTimeout(800)
    const addBtn = page.getByRole('button', { name: '添加员工' })
    const manualBtn = page.getByRole('button', { name: /手动填写/ })
    await addBtn.waitFor({ state: 'visible', timeout: 10_000 })
    note('add-employee-button', await addBtn.isVisible())
    note('manual-backup-button', await manualBtn.isVisible())
    const pageText = await page.locator('.company-admin-page').innerText()
    note('identity-and-send-columns', /身份/.test(pageText) && /发送验证/.test(pageText), {
      hasIdentity: /身份已确认|身份未确认|身份/.test(pageText),
      hasSend: /发送已验证|发送未验证|发送验证/.test(pageText)
    })
    await addBtn.click()
    await page.waitForSelector('.ed-dialog, .el-dialog', { timeout: 10_000 })
    const dialog = page.locator('.ed-dialog, .el-dialog').filter({ hasText: '添加员工' }).last()
    const confirmBtn = dialog.getByRole('button', { name: '确认身份' })
    note('employee-dialog-open', await dialog.isVisible())
    note('confirm-disabled-before-select', await confirmBtn.isDisabled())
    await dialog.locator('input').first().fill('李')
    await dialog.getByRole('button', { name: '搜索' }).click()
    await page.waitForTimeout(2500)
    const rows = dialog.locator('.ed-table__body tr, .el-table__body tr.el-table__row, tbody tr')
    const rowCount = await rows.count()
    note('search-shows-matches', rowCount > 0, { rowCount })
    note('confirm-still-disabled-before-click', await confirmBtn.isDisabled())
    if (rowCount > 0) {
      await rows.nth(Math.min(1, rowCount - 1)).click()
      await page.waitForTimeout(300)
      note('confirm-enabled-after-select', await confirmBtn.isEnabled())
    }
    const dialogText = await dialog.innerText()
    note('matches-have-disambiguation', /…/.test(dialogText) || /部门/.test(dialogText), {
      hasTail: /…/.test(dialogText),
      hasDeptHeader: /部门/.test(dialogText)
    })
    await dialog.getByRole('button', { name: '取消' }).click()
    await manualBtn.click()
    await page.waitForSelector('.ed-dialog:has-text("手动填写"), .el-dialog:has-text("手动填写")', { timeout: 10_000 })
    const typeOptions = await page.locator('.ed-dialog .ed-select, .el-dialog .el-select').first().count()
    note('manual-dialog-keeps-chat', typeOptions > 0)
  } catch (err) {
    note('ui-exception', false, { error: err.message })
  } finally {
    await browser.close()
  }
  if (!report.pass) process.exit(1)
}

main()
