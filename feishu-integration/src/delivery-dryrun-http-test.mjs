import http from 'http'
import { CONFIG } from './config.mjs'
import { createServer } from './server.mjs'
import { closePool, query } from './db.mjs'

function assert(cond, msg) {
  if (!cond) throw new Error(msg)
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

async function request(server, { method, path, token, body }) {
  const addr = server.address()
  const payload = body ? JSON.stringify(body) : null
  const headers = { 'content-type': 'application/json' }
  if (token) headers.authorization = `Bearer ${token}`
  return new Promise((resolve, reject) => {
    const req = http.request(
      {
        hostname: '127.0.0.1',
        port: addr.port,
        path,
        method,
        headers
      },
      res => {
        const chunks = []
        res.on('data', chunk => chunks.push(chunk))
        res.on('end', () => {
          const raw = Buffer.concat(chunks).toString('utf8')
          let json = null
          try {
            json = raw ? JSON.parse(raw) : null
          } catch {
            json = { raw }
          }
          resolve({ status: res.statusCode, json, raw })
        })
      }
    )
    req.on('error', reject)
    if (payload) req.write(payload)
    req.end()
  })
}

function containsSecret(text) {
  const hay = String(text || '').toLowerCase()
  return hay.includes('tenant_access_token') || hay.includes('app_secret') || /kw2q/.test(hay) || hay.includes('bearer ')
}

async function main() {
  const token = 'phase2b-dryrun-token'
  CONFIG.internalToken = token
  const rows = await query(
    `SELECT id, name, status
       FROM company_feishu_subscription
      WHERE status = 'ENABLED'
      ORDER BY id
      LIMIT 5`
  )
  assert(rows.length > 0, 'need an ENABLED subscription in mysql-de for dry-run')
  const subscriptionId = String(rows[0].id)

  const server = await new Promise(resolve => {
    const s = createServer()
    s.listen(0, '127.0.0.1', () => resolve(s))
  })
  try {
    const badSub = await request(server, {
      method: 'POST',
      path: '/v1/deliveries',
      token,
      body: { subscriptionId: '0', dryRun: true, operatorUserId: 1 }
    })
    assert(badSub.status >= 400, `missing subscription should fail, got ${badSub.status}`)
    assert(!containsSecret(badSub.raw), 'secret leaked in missing-subscription response')

    const started = await request(server, {
      method: 'POST',
      path: '/v1/deliveries',
      token,
      body: { subscriptionId, dryRun: true, operatorUserId: 1 }
    })
    assert(started.status === 202, `dry-run start should 202, got ${started.status} ${started.raw}`)
    assert(started.json.runId, 'runId missing')
    assert(!containsSecret(started.raw), 'secret leaked in start response')
    const runId = started.json.runId

    let detail = null
    for (let i = 0; i < 40; i++) {
      detail = await request(server, {
        method: 'GET',
        path: `/v1/deliveries/${runId}`,
        token
      })
      if (
        detail.status === 200 &&
        detail.json.status &&
        detail.json.status !== 'PENDING' &&
        detail.json.status !== 'RUNNING'
      ) {
        break
      }
      await sleep(500)
    }
    assert(detail.status === 200, `run lookup failed: ${detail.status} ${detail.raw}`)
    assert(detail.json.runId === runId, 'runId mismatch')
    assert(detail.json.status === 'SUCCEEDED', `expected SUCCEEDED, got ${detail.json.status}`)
    assert(detail.json.executionMode === 'DRY_RUN', `expected DRY_RUN, got ${detail.json.executionMode}`)
    assert(detail.json.screenshotStatus === 'SKIPPED', 'dry-run must skip screenshot')
    assert(Array.isArray(detail.json.targets) && detail.json.targets.length >= 1, 'targets missing')
    assert(
      detail.json.targets.every(row => row.status === 'SUCCEEDED' && row.executionMode === 'DRY_RUN' && !row.messageId),
      'dry-run must not send Feishu messages or look like LIVE'
    )
    assert(!containsSecret(detail.raw), 'secret leaked in run detail')
    console.log(
      `[delivery-dryrun-http-test] PASS runId=${runId} subscriptionId=${subscriptionId} targets=${detail.json.targets.length}`
    )
  } finally {
    await new Promise(resolve => server.close(resolve))
    await closePool().catch(() => {})
  }
}

main().catch(err => {
  console.error('[delivery-dryrun-http-test] FAIL', err.message)
  closePool().catch(() => {})
  process.exit(1)
})
