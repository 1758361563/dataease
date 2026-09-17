import { chromium } from 'playwright'
import fs from 'fs'
import path from 'path'
import { fileURLToPath } from 'url'
import { CONFIG } from './config.mjs'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const OUT = path.join(__dirname, '..', 'tmp', 'phase2b_http_smoke.json')
const BASE_DE2 = 'http://localhost:8101/de2api'
const BASE_API = 'http://localhost:8080/api'

async function loginToken(user, password) {
  const browser = await chromium.launch({ headless: true, args: ['--disable-dev-shm-usage'] })
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 } })
  try {
    await page.goto(`${CONFIG.baseUrl}/#/login`, { waitUntil: 'domcontentloaded', timeout: 60_000 })
    await page.waitForSelector('.login-form input', { timeout: 30_000 })
    await page.waitForTimeout(600)
    const inputs = page.locator('.login-form input')
    await inputs.nth(0).fill(user)
    await inputs.nth(1).fill(password)
    await page.locator('.login-btn button').first().click()
    await page.waitForURL(/workbranch|panel|index/, { timeout: 30_000 })
    const token = await page.evaluate(() => {
      const raw = localStorage.getItem('de_v2_user.token')
      if (!raw) return null
      try {
        const parsed = JSON.parse(raw)
        const value = parsed?.v
        if (typeof value === 'string') {
          try {
            return JSON.parse(value)
          } catch {
            return value
          }
        }
        return raw
      } catch {
        return raw
      }
    })
    if (!token) throw new Error(`no token for ${user}`)
    return token
  } finally {
    await page.close()
    await browser.close()
  }
}

async function call(base, token, method, urlPath, body) {
  const headers = { 'X-DE-TOKEN': token }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const res = await fetch(base + urlPath, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body)
  })
  const text = await res.text()
  let json = null
  try {
    json = JSON.parse(text)
  } catch {
    json = { raw: text.slice(0, 300) }
  }
  return { status: res.status, json }
}

function unwrap(json) {
  if (json && typeof json === 'object' && 'data' in json && 'code' in json) return json.data
  return json
}

async function main() {
  const report = { startedAt: new Date().toISOString(), steps: [], pass: true }
  const note = (name, ok, extra) => {
    report.steps.push({ name, ok, ...extra })
    if (!ok) report.pass = false
    console.log(`${ok ? 'PASS' : 'FAIL'} ${name}`, extra ? JSON.stringify(extra) : '')
  }

  const adminToken = await loginToken(CONFIG.user, CONFIG.password)
  note('admin-login', !!adminToken)

  const unauth = await call(BASE_DE2, '', 'GET', '/company/bi/feishu/integration')
  note('unauth-integration', unauth.status === 401 || unauth.json?.code === 80001 || unauth.status === 403, {
    http: unauth.status,
    code: unauth.json?.code
  })

  const integration = await call(BASE_DE2, adminToken, 'GET', '/company/bi/feishu/integration')
  const integrationData = unwrap(integration.json)
  note('admin-integration', integration.status === 200 && integrationData && !JSON.stringify(integrationData).toLowerCase().includes('kw2q'), {
    http: integration.status,
    configured: integrationData?.configured,
    appSecretRef: integrationData?.appSecretRef,
    hasSecret: /secret|token/i.test(JSON.stringify(integrationData || {})) && JSON.stringify(integrationData).includes('KW2q')
  })

  const recipients = await call(BASE_DE2, adminToken, 'GET', '/company/bi/feishu/recipients')
  const recipientRows = unwrap(recipients.json) || []
  note('admin-recipients', Array.isArray(recipientRows) && recipientRows.length >= 2, {
    http: recipients.status,
    count: Array.isArray(recipientRows) ? recipientRows.length : -1
  })

  const subs = await call(BASE_DE2, adminToken, 'GET', '/company/bi/feishu/subscriptions')
  const subRows = unwrap(subs.json) || []
  const seed = subRows.find(s => String(s.id) === '7506245945263848777') || subRows.find(s => s.status === 'ENABLED') || subRows[0]
  note('admin-subscriptions', Array.isArray(subRows) && !!seed, {
    http: subs.status,
    count: Array.isArray(subRows) ? subRows.length : -1,
    seedId: seed?.id,
    seedStatus: seed?.status
  })

  let runId = null
  if (seed) {
    if (seed.status !== 'ENABLED') {
      await call(BASE_DE2, adminToken, 'POST', `/company/bi/feishu/subscriptions/${seed.id}/enable`)
    }
    const run = await call(BASE_DE2, adminToken, 'POST', `/company/bi/feishu/subscriptions/${seed.id}/run`, { dryRun: true })
    const runData = unwrap(run.json)
    runId = runData?.runId
    note('admin-dry-run', run.status === 200 && !!runId && runData?.accepted === true, {
      http: run.status,
      runId,
      status: runData?.status,
      msg: run.json?.msg
    })
    if (runId) {
      let detail = null
      for (let i = 0; i < 30; i++) {
        const got = await call(BASE_DE2, adminToken, 'GET', `/company/bi/feishu/subscriptions/${seed.id}/runs/${runId}`)
        detail = unwrap(got.json)
        if (detail && ['SUCCEEDED', 'FAILED', 'SKIPPED', 'UNKNOWN'].includes(detail.status)) break
        await new Promise(r => setTimeout(r, 1500))
      }
      const targets = detail?.targets || []
      note('admin-run-detail', !!detail && Array.isArray(targets) && targets.length >= 1, {
        runStatus: detail?.status,
        targetCount: targets.length,
        targetStatuses: targets.map(t => `${t.receiveIdType || t.recipientType}:${t.status}`)
      })
    }
  }

  const viaVite = await call(BASE_API, adminToken, 'GET', '/company/bi/feishu/integration')
  note('vite-proxy-de2api', viaVite.status === 200 && unwrap(viaVite.json)?.configured !== undefined, {
    http: viaVite.status
  })

  await call(BASE_DE2, adminToken, 'POST', '/company/bi/admin/users/900001/resetPassword', { password: 'Normal@123456' })
  await call(BASE_DE2, adminToken, 'POST', '/company/bi/admin/users/900002/resetPassword', { password: 'Readonly@123456' })
  await call(BASE_DE2, adminToken, 'POST', '/company/bi/admin/users/900011/resetPassword', { password: 'Admin2@123456' })

  try {
    const normalToken = await loginToken('p1_normal', 'Normal@123456')
    const readonlyToken = await loginToken('p1_readonly', 'Readonly@123456')
    const otherAdminToken = await loginToken('p2_admin', 'Admin2@123456')

    const normalGet = await call(BASE_DE2, normalToken, 'GET', '/company/bi/feishu/recipients')
    const readonlyGet = await call(BASE_DE2, readonlyToken, 'GET', '/company/bi/feishu/recipients')
    note('normal-denied', normalGet.status === 403 || normalGet.json?.code === 70001, { http: normalGet.status, code: normalGet.json?.code })
    note('readonly-denied', readonlyGet.status === 403 || readonlyGet.json?.code === 70001, { http: readonlyGet.status, code: readonlyGet.json?.code })

    const otherAdminGet = await call(BASE_DE2, otherAdminToken, 'GET', '/company/bi/feishu/integration')
    const otherAdminWrite = await call(BASE_DE2, otherAdminToken, 'POST', '/company/bi/feishu/test')
    const otherAdminPut = await call(BASE_DE2, otherAdminToken, 'POST', '/company/bi/feishu/integration', {
      appId: 'should-not-write',
      appSecretRef: 'FEISHU_APP_SECRET'
    })
    note('other-admin-can-read', otherAdminGet.status === 200, { http: otherAdminGet.status })
    note('other-admin-cannot-test-integration', otherAdminWrite.status === 403 || otherAdminWrite.json?.code === 70001, {
      http: otherAdminWrite.status,
      code: otherAdminWrite.json?.code
    })
    const writeDenied = otherAdminPut.status === 403 || otherAdminPut.json?.code === 70001
    note('other-admin-cannot-write-integration', writeDenied || otherAdminPut.status === 405, {
      http: otherAdminPut.status,
      code: otherAdminPut.json?.code,
      note: 'POST mapping for integration is the supported method; PUT is blocked by TokenFilter'
    })
  } catch (err) {
    note('role-logins', false, { detail: err.message })
  }

  report.finishedAt = new Date().toISOString()
  fs.mkdirSync(path.dirname(OUT), { recursive: true })
  fs.writeFileSync(OUT, JSON.stringify(report, null, 2))
  console.log(report.pass ? 'PHASE2B_HTTP_SMOKE=PASS' : 'PHASE2B_HTTP_SMOKE=FAIL')
  console.log('wrote', OUT)
  if (!report.pass) process.exit(1)
}

main().catch(err => {
  console.error(err)
  process.exit(1)
})
