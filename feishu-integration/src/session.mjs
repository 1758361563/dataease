import { chromium } from 'playwright'
import { CONFIG, requireScreenshotPassword } from './config.mjs'

let browser
let context
let bootPromise
let queue = Promise.resolve()

export function enqueue(fn) {
  const run = queue.then(fn, fn)
  queue = run.catch(err => {
    console.error('[feishu-shot] job queue error', err)
  })
  return run
}

async function ensureSession() {
  requireScreenshotPassword()
  if (context) return context
  if (bootPromise) return bootPromise
  bootPromise = (async () => {
    browser = await chromium.launch({
      headless: true,
      args: ['--disable-dev-shm-usage', '--disable-gpu']
    })
    context = await browser.newContext({
      viewport: CONFIG.viewport,
      locale: 'zh-CN'
    })
    const page = await context.newPage()
    try {
      await login(page)
    } finally {
      await page.close()
    }
    return context
  })().catch(err => {
    bootPromise = null
    context = null
    throw err
  })
  return bootPromise
}

async function login(page) {
  const { baseUrl, user, password } = CONFIG
  await page.goto(`${baseUrl}/#/login`, { waitUntil: 'domcontentloaded', timeout: 60_000 })
  await page.waitForSelector('.login-form input', { timeout: 30_000 })
  await page.waitForTimeout(800)
  const inputs = page.locator('.login-form input')
  await inputs.nth(0).fill(user)
  await inputs.nth(1).fill(password)
  await page.locator('.login-btn button').first().click()
  await page.waitForURL(/workbranch|panel|index/, { timeout: 30_000 })
  const token = await page.evaluate(() => localStorage.getItem('de_v2_user.token'))
  if (!token) {
    throw new Error('Login succeeded in URL but de_v2_user.token is missing; screenshot session is not authenticated')
  }
  console.log('[feishu-shot] logged in via UI; token kept in memory only')
}

export async function withPage(fn) {
  const ctx = await ensureSession()
  const page = await ctx.newPage()
  try {
    return await fn(page)
  } finally {
    await page.close().catch(() => {})
  }
}

export async function closeBrowser() {
  bootPromise = null
  if (context) await context.close().catch(() => {})
  if (browser) await browser.close().catch(() => {})
  context = null
  browser = null
}

export async function invalidateSession() {
  await closeBrowser()
}
