import { chromium } from 'playwright'
import { CONFIG } from './config.mjs'

function assert(cond, msg) {
  if (!cond) throw new Error(msg)
}

async function dismissOverlays(page) {
  for (let i = 0; i < 6; i++) {
    const overlay = page.locator('text=plugin:vite-plugin-eslint')
    if (await overlay.count()) {
      await page.keyboard.press('Escape')
      await page.waitForTimeout(250)
      continue
    }
    break
  }
}

async function login(page) {
  await page.goto(`${CONFIG.baseUrl}/#/login`, { waitUntil: 'domcontentloaded', timeout: 60_000 })
  await dismissOverlays(page)
  await page.waitForSelector('.login-form input', { timeout: 30_000 })
  const inputs = page.locator('.login-form input')
  await inputs.nth(0).fill(CONFIG.user)
  await inputs.nth(1).fill(CONFIG.password)
  await page.locator('.login-btn button').first().click()
  await page.waitForURL(/workbranch|panel|index|company-admin/, { timeout: 30_000 })
}

async function main() {
  assert(CONFIG.password, 'screenshot password is missing from the environment')
  const browser = await chromium.launch({ headless: true })
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  try {
    await login(page)
    await dismissOverlays(page)
    await page.evaluate(async () => {
      const app = document.querySelector('#app')?.__vue_app__
      const router = app?.config?.globalProperties?.$router
      if (!router) throw new Error('no-router')
      await router.push('/company-admin/feishu')
    })
    await page.waitForSelector('.company-admin-page', { timeout: 20_000 })
    await dismissOverlays(page)
    const text = await page.locator('.company-admin-page').innerText()
    assert(text.includes('发送隔离'), 'admin page is missing the lane column')
    assert(text.includes('数据更新'), 'admin page is missing the data-update column')
    assert(text.includes('数据更新状态未经校验'), 'unlinked subscriptions must show the unchecked label')
    assert(text.includes('测试'), 'existing subscriptions must stay on the test lane')
    console.log('[stability-ui-check] PASS')
  } finally {
    await browser.close()
  }
}

main().catch(err => {
  console.error('[stability-ui-check] FAIL', err.message)
  process.exit(1)
})
