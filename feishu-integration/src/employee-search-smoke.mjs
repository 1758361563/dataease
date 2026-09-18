import { chromium } from 'playwright'
import fs from 'fs'
import path from 'path'
import { fileURLToPath } from 'url'
import { CONFIG } from './config.mjs'
import { ensureIdentityStatusSchema } from './store.mjs'
import { query } from './db.mjs'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const OUT = path.join(__dirname, '..', 'tmp', 'employee_search_smoke.json')
const BASE_DE2 = 'http://localhost:8101/de2api'

function redactEmployee(row) {
  return {
    hasName: !!row?.name,
    nameLength: String(row?.name || '').length,
    hasUserId: !!row?.userId,
    userIdLength: String(row?.userId || '').length,
    userIdLooksPersonal: !!row?.userId && !String(row.userId).startsWith('ou_') && !String(row.userId).startsWith('oc_'),
    departmentCount: Array.isArray(row?.departments) ? row.departments.length : 0,
    hasUserIdTail: !!row?.userIdTail,
    leakedMobile: Object.prototype.hasOwnProperty.call(row || {}, 'mobile'),
    leakedEmail: Object.prototype.hasOwnProperty.call(row || {}, 'email')
  }
}

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
    return { token, page, browser }
  } catch (err) {
    await page.close()
    await browser.close()
    throw err
  }
}

async function call(token, method, urlPath, body) {
  const headers = { 'X-DE-TOKEN': token }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const res = await fetch(BASE_DE2 + urlPath, {
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
  const report = { startedAt: new Date().toISOString(), steps: [], pass: true, dryRun: CONFIG.dryRun }
  const note = (name, ok, extra) => {
    report.steps.push({ name, ok, ...extra })
    if (!ok) report.pass = false
    console.log(`${ok ? 'PASS' : 'FAIL'} ${name}`, extra ? JSON.stringify(extra) : '')
  }

  await ensureIdentityStatusSchema()
  const tokenHeader = CONFIG.internalToken
  if (!tokenHeader) throw new Error('FEISHU_INTERNAL_TOKEN missing')

  const independentDenied = await fetch('http://127.0.0.1:8091/v1/employees/search?query=%E6%9D%8E')
  note('independent-search-requires-token', independentDenied.status === 401, { status: independentDenied.status })

  const queryChar = process.env.FEISHU_EMPLOYEE_SEARCH_QUERY || '李'
  const searchRes = await fetch(
    `http://127.0.0.1:8091/v1/employees/search?query=${encodeURIComponent(queryChar)}`,
    { headers: { Authorization: `Bearer ${tokenHeader}` } }
  )
  const searchJson = await searchRes.json()
  const matches = Array.isArray(searchJson.matches) ? searchJson.matches : []
  note('independent-search-http', searchRes.status === 200 && !searchJson.error, {
    status: searchRes.status,
    error: searchJson.error || null,
    code: searchJson.code || null,
    missingScope: searchJson.missingScope || null,
    matchCount: matches.length
  })
  if (searchRes.status !== 200) {
    fs.writeFileSync(OUT, JSON.stringify(report, null, 2))
    throw new Error(searchJson.error || 'independent search failed')
  }
  const fields = matches.map(redactEmployee)
  const sameNameGroups = new Map()
  for (const row of matches) {
    const key = String(row.name || '')
    sameNameGroups.set(key, (sameNameGroups.get(key) || 0) + 1)
  }
  const duplicateNameCount = [...sameNameGroups.values()].filter(n => n > 1).length
  note('independent-search-fields', fields.every(row => row.hasName && row.hasUserId && row.userIdLooksPersonal && !row.leakedMobile && !row.leakedEmail), {
    sample: fields[0] || null,
    duplicateNameGroups: duplicateNameCount,
    returnedFullRoster: matches.length > 20
  })

  const { token, browser } = await loginToken(CONFIG.user, CONFIG.password)
  try {
    const bff = await call(token, 'GET', `/company/bi/feishu/employees/search?query=${encodeURIComponent(queryChar)}`)
    const bffData = unwrap(bff.json) || {}
    const bffMatches = Array.isArray(bffData.matches) ? bffData.matches : []
    note('bff-search', bff.status === 200 && bff.json?.code === 0, {
      http: bff.status,
      code: bff.json?.code,
      matchCount: bffMatches.length,
      leakedMobile: bffMatches.some(row => Object.prototype.hasOwnProperty.call(row, 'mobile'))
    })

    const existing = await query(
      `SELECT feishu_id FROM company_feishu_recipient WHERE receive_id_type='user_id' AND status='ENABLED'`
    )
    const existingIds = new Set(existing.map(row => String(row.feishu_id)))
    const createTarget = bffMatches.find(row => row.userId && !existingIds.has(String(row.userId))) || bffMatches[0]
    if (!createTarget?.userId) {
      note('confirm-skipped', false, { reason: 'no searchable employee returned' })
    } else {
      const before = await query(
        `SELECT id, identity_status, verify_status, source FROM company_feishu_recipient WHERE receive_id_type='user_id' AND feishu_id=?`,
        [createTarget.userId]
      )
      const confirmed = await call(token, 'POST', '/company/bi/feishu/employees/confirm', { userId: createTarget.userId })
      const data = unwrap(confirmed.json) || {}
      const confirmOk = confirmed.status === 200 && confirmed.json?.code === 0 && data.identityStatus === 'CONFIRMED'
      note('confirm-identity', confirmOk, {
        http: confirmed.status,
        code: confirmed.json?.code,
        identityStatus: data.identityStatus,
        verifyStatus: data.verifyStatus,
        sendVerified: data.sendVerified,
        created: data.created,
        identityJustConfirmed: data.identityJustConfirmed,
        previouslyExisted: before.length > 0
      })
      const sendNotAutoVerified = data.verifyStatus !== 'VERIFIED' || before[0]?.verify_status === 'VERIFIED'
      note('confirm-does-not-mark-send-success', sendNotAutoVerified && data.identityJustConfirmed === true, {
        verifyStatus: data.verifyStatus,
        sendVerified: data.sendVerified
      })
      if (data.created === true && data.id) {
        await call(token, 'POST', `/company/bi/feishu/recipients/${data.id}/disable`)
        note('new-test-recipient-disabled', true, { disabled: true })
      }
    }

    await browser.close()
  } catch (err) {
    await browser.close().catch(() => {})
    throw err
  }

  report.finishedAt = new Date().toISOString()
  fs.mkdirSync(path.dirname(OUT), { recursive: true })
  fs.writeFileSync(OUT, JSON.stringify(report, null, 2))
  if (!report.pass) process.exit(1)
}

main().catch(err => {
  console.error(err)
  process.exit(1)
})
