import http from 'http'
import fs from 'fs'
import { CONFIG, ensureDirs } from './config.mjs'
import { createTask, getTask, publicTask, sweepExpired, updateTask } from './tasks.mjs'

function send(res, status, body, headers = {}) {
  const payload = typeof body === 'string' ? body : JSON.stringify(body, null, 2)
  res.writeHead(status, {
    'content-type': typeof body === 'string' && !headers['content-type'] ? 'text/plain; charset=utf-8' : 'application/json; charset=utf-8',
    ...headers
  })
  res.end(payload)
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = []
    req.on('data', chunk => chunks.push(chunk))
    req.on('end', () => {
      const raw = Buffer.concat(chunks).toString('utf8')
      if (!raw) return resolve({})
      try {
        resolve(JSON.parse(raw))
      } catch (err) {
        reject(err)
      }
    })
    req.on('error', reject)
  })
}

function bearerToken(req) {
  const header = req.headers.authorization || req.headers.Authorization || ''
  const match = String(header).match(/^Bearer\s+(.+)$/i)
  return match ? match[1].trim() : ''
}

function requireInternalToken(req, res) {
  if (!CONFIG.internalToken) {
    send(res, 503, { error: 'FEISHU_INTERNAL_TOKEN is not configured' })
    return false
  }
  const provided = bearerToken(req)
  if (!provided || provided !== CONFIG.internalToken) {
    send(res, 401, { error: 'unauthorized' })
    return false
  }
  return true
}

function runCapture(task) {
  updateTask(task.taskId, { status: 'RUNNING' })
  return import('./session.mjs').then(({ enqueue }) =>
    enqueue(async () => {
      try {
        const { captureCard } = await import('./capture.mjs')
        const result = await captureCard({ ...task.input, taskId: task.taskId })
        updateTask(task.taskId, {
          status: 'SUCCEEDED',
          pngPath: result.pngPath,
          meta: result.meta,
          error: null
        })
      } catch (err) {
        updateTask(task.taskId, {
          status: 'FAILED',
          error: err.message || String(err)
        })
      }
    })
  )
}

async function defaultStartDelivery(body) {
  const { executeSubscription, startManualRun } = await import('./run-subscription.mjs')
  const claimed = await startManualRun({
    subscriptionId: body.subscriptionId,
    operatorUserId: body.operatorUserId,
    dryRun: body.dryRun === true,
    triggerType: body.triggerType || 'MANUAL',
    plannedAt: body.plannedAt
  })
  const runWork = () =>
    executeSubscription({
      retryRunId: claimed.run.id,
      subscriptionId: body.subscriptionId,
      dryRun: body.dryRun === true,
      closeResources: false
    }).catch(err => {
      console.error('[feishu-shot] delivery failed', err.message)
    })
  if (body.dryRun === true) {
    setImmediate(runWork)
  } else {
    const { enqueue } = await import('./session.mjs')
    enqueue(runWork)
  }
  return { runId: String(claimed.run.id), status: claimed.run.status, created: claimed.created }
}

export function createServer(hooks = {}) {
  ensureDirs()
  const startDelivery = hooks.startDelivery || defaultStartDelivery
  return http.createServer(async (req, res) => {
    try {
      sweepExpired(CONFIG.taskTtlMs)
      const url = new URL(req.url, `http://127.0.0.1:${CONFIG.port}`)
      if (req.method === 'GET' && url.pathname === '/health') {
        return send(res, 200, { ok: true, service: 'feishu-screenshot', port: CONFIG.port })
      }
      if (req.method === 'POST' && url.pathname === '/v1/deliveries') {
        if (!requireInternalToken(req, res)) return
        const body = await readBody(req)
        if (!body.subscriptionId) return send(res, 400, { error: 'subscriptionId is required' })
        const started = await startDelivery(body)
        res.writeHead(202, { 'content-type': 'application/json; charset=utf-8' })
        return res.end(JSON.stringify(started))
      }
      const deliveryMatch = url.pathname.match(/^\/v1\/deliveries\/([^/]+)$/)
      if (req.method === 'GET' && deliveryMatch) {
        if (!requireInternalToken(req, res)) return
        const { getDeliveryRunById, listDeliveryTargets } = await import('./store.mjs')
        const { inferExecutionMode, inferTargetExecutionMode } = await import('./execution-mode.mjs')
        const run = await getDeliveryRunById(deliveryMatch[1])
        if (!run) return send(res, 404, { error: 'run not found' })
        const targets = await listDeliveryTargets(run.id)
        const executionMode = inferExecutionMode(run, targets)
        return send(res, 200, {
          runId: String(run.id),
          status: run.status,
          executionMode,
          screenshotStatus: run.screenshot_status,
          uploadStatus: run.upload_status,
          errorSummary: run.error_summary,
          targets: targets.map(row => ({
            recipientId: String(row.recipient_id),
            receiveIdType: row.receive_id_type,
            status: row.status,
            executionMode: inferTargetExecutionMode(row, executionMode),
            messageId: row.feishu_message_id,
            errorSummary: row.error_summary
          }))
        })
      }
      if (req.method === 'POST' && url.pathname === '/v1/screenshots') {
        const body = await readBody(req)
        const task = createTask({
          dashboardId: body.dashboardId,
          chartId: body.chartId,
          filters: body.filters || null
        })
        runCapture(task)
        res.writeHead(202, { 'content-type': 'application/json; charset=utf-8' })
        return res.end(JSON.stringify(publicTask(task), null, 2))
      }
      const shotMatch = url.pathname.match(/^\/v1\/screenshots\/([^/]+)$/)
      if (req.method === 'GET' && shotMatch) {
        const task = getTask(shotMatch[1])
        if (!task) return send(res, 404, { error: 'task not found' })
        return send(res, 200, publicTask(task))
      }
      const imgMatch = url.pathname.match(/^\/v1\/screenshots\/([^/]+)\/image$/)
      if (req.method === 'GET' && imgMatch) {
        const task = getTask(imgMatch[1])
        if (!task) return send(res, 404, { error: 'task not found' })
        if (task.status !== 'SUCCEEDED' || !task.pngPath || !fs.existsSync(task.pngPath)) {
          return send(res, 409, { error: 'image not ready', status: task.status })
        }
        res.writeHead(200, { 'content-type': 'image/png' })
        fs.createReadStream(task.pngPath).pipe(res)
        return
      }
      send(res, 404, { error: 'not found' })
    } catch (err) {
      send(res, 500, { error: err.message || String(err) })
    }
  })
}

export function startServer(hooks) {
  const server = createServer(hooks)
  return new Promise(resolve => {
    server.listen(CONFIG.port, '127.0.0.1', () => {
      console.log(`[feishu-shot] listening on http://127.0.0.1:${CONFIG.port}`)
      resolve(server)
    })
  })
}
