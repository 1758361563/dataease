import http from 'http'
import { CONFIG } from './config.mjs'
import { createServer } from './server.mjs'

function assert(cond, msg) {
  if (!cond) throw new Error(msg)
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
          resolve({ status: res.statusCode, json })
        })
      }
    )
    req.on('error', reject)
    if (payload) req.write(payload)
    req.end()
  })
}

async function main() {
  const previous = CONFIG.internalToken
  CONFIG.internalToken = 'phase2b-test-token'
  const started = []
  const server = await new Promise(resolve => {
    const s = createServer({
      startDelivery: async body => {
        started.push(body)
        return { runId: '7500000000000000001', status: 'PENDING', created: true }
      }
    })
    s.listen(0, '127.0.0.1', () => resolve(s))
  })
  try {
    const health = await request(server, { method: 'GET', path: '/health' })
    assert(health.status === 200 && health.json.ok === true, 'health should be open')

    const noToken = await request(server, {
      method: 'POST',
      path: '/v1/deliveries',
      body: { subscriptionId: '1' }
    })
    assert(noToken.status === 401, `missing token should 401, got ${noToken.status}`)

    const getNoToken = await request(server, { method: 'GET', path: '/v1/deliveries/1' })
    assert(getNoToken.status === 401, `GET without token should 401, got ${getNoToken.status}`)

    const badToken = await request(server, {
      method: 'POST',
      path: '/v1/deliveries',
      token: 'wrong',
      body: { subscriptionId: '1' }
    })
    assert(badToken.status === 401, `bad token should 401, got ${badToken.status}`)

    const ok = await request(server, {
      method: 'POST',
      path: '/v1/deliveries',
      token: 'phase2b-test-token',
      body: { subscriptionId: '7506245945263848777', dryRun: true, operatorUserId: 1 }
    })
    assert(ok.status === 202, `delivery should 202, got ${ok.status}`)
    assert(ok.json.runId === '7500000000000000001', 'runId should be returned')
    assert(started.length === 1, 'startDelivery should be called once')
    assert(started[0].dryRun === true, 'dryRun should pass through')
    assert(!JSON.stringify(ok.json).includes('phase2b-test-token'), 'token must not appear in response')
    console.log('[delivery-http-test] PASS')
  } finally {
    CONFIG.internalToken = previous
    await new Promise(resolve => server.close(resolve))
  }
}

main().catch(err => {
  console.error('[delivery-http-test] FAIL', err.message)
  process.exit(1)
})
