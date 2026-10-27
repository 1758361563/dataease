// Private BI child process. stdin/stdout are a JSON-line RPC channel; no listening port.
import readline from 'node:readline'
import fs from 'node:fs'
import { format } from 'node:util'
const write = body => process.stdout.write(JSON.stringify(body) + '\n')
// Existing capture/scheduler diagnostics belong in stderr, never in the RPC stream.
console.log = (...args) => process.stderr.write(format(...args) + '\n')
console.info = console.log

const { CONFIG } = await import('./config.mjs')
const { closePool, getPool, query, metadataLockName } = await import('./db.mjs')
const { refreshManagedConnection } = await import('./managed-connection.mjs')
const { searchEmployees, resolveEmployee } = await import('./contact.mjs')
const { getTenantAccessToken } = await import('./feishu.mjs')
const { startManualRun, executeSubscription } = await import('./run-subscription.mjs')
const { schedulerTick } = await import('./scheduler.mjs')
const { withSchedulerLock } = await import('./store.mjs')
const { closeBrowser } = await import('./session.mjs')
const { chromium } = await import('playwright')

let queue = Promise.resolve()
let queuedWork = 0
let shuttingDown = false
let timer
let lease
const ownerLock = metadataLockName('worker')
let lastSchedulerError = null
function serialize(fn) {
  queuedWork++
  const current = queue.then(fn).finally(() => { queuedWork-- })
  queue = current.catch(() => {})
  return current
}
function publicError(err) {
  // Avoid returning raw process/SQL diagnostics, tokens or request payloads to browsers.
  if (err?.missingScope) return `飞书通讯录权限不足：${err.missingScope}`
  if (err?.status === 404) return '通讯录中未找到该员工'
  if (err?.code && Number.isInteger(Number(err.code))) return `飞书接口调用失败，错误码 ${err.code}`
  return '飞书操作失败，请检查应用凭据、权限、数据库或截图账号配置'
}
async function withConfig(fn) {
  const config = await refreshManagedConnection()
  if (config.runtimeEnabled !== true) throw new Error('connection disabled')
  return fn(config)
}
async function handle(method, args) {
  if (method === 'status') return { running: true, browserInstalled: fs.existsSync(chromium.executablePath()), lastSchedulerError }
  return withConfig(async config => {
    if (method === 'search') return { matches: await searchEmployees(args.query) }
    if (method === 'resolve') return resolveEmployee(args.userId)
    if (method === 'test') {
      const auth = await getTenantAccessToken(CONFIG.feishuAppId, CONFIG.feishuAppSecret)
      // A read-only real API call, no test message or delivery side effect.
      const response = await fetch('https://open.feishu.cn/open-apis/contact/v3/departments/0/children?page_size=1&department_id_type=department_id', {
        headers: { Authorization: `Bearer ${auth.token}` }, signal: AbortSignal.timeout(15000)
      })
      const body = await response.json()
      return { ok: true, appConnected: true, directoryConnected: response.ok && body.code === 0,
        directoryErrorCode: body.code || null, browserInstalled: fs.existsSync(chromium.executablePath()),
        checked: '已真实验证飞书应用认证；通讯录权限和浏览器状态分别显示', feishuCalled: true }
    }
    if (method === 'start') {
      if (args.dryRun === true) throw new Error('Managed subscriptions do not support simulated delivery')
      const claimed = await startManualRun({ ...args, subscriptionId: String(args.subscriptionId), dryRun: false })
      // The acceptance response precedes execution. Execute through the same DB lock as the scheduler.
      setImmediate(() => serialize(() => withConfig(() => withSchedulerLock(() => executeSubscription({
        retryRunId: String(claimed.run.id), subscriptionId: String(args.subscriptionId), dryRun: false, closeResources: false
      })))).catch(async () => {
        lastSchedulerError = '执行失败，请查看运行记录'
        await query("UPDATE company_feishu_delivery_run SET status='FAILED',error_summary=?,finished_at=? WHERE id=? AND status='PENDING'",
          ['BI 内部执行器未能启动任务，请检查应用连接', Date.now(), String(claimed.run.id)]).catch(() => {})
      }))
      return { runId: String(claimed.run.id), status: claimed.run.status, accepted: true }
    }
    throw new Error('Unsupported worker operation')
  })
}
async function shutdown() {
  if (shuttingDown) return
  shuttingDown = true
  clearInterval(timer)
  await queue
  await closeBrowser()
  if (lease) { await lease.query('SELECT RELEASE_LOCK(?)', [ownerLock]); lease.release() }
  await closePool()
}
try {
  lease = await (await getPool()).getConnection()
  const [rows] = await lease.query('SELECT GET_LOCK(?, 0) AS acquired', [ownerLock])
  if (Number(rows[0].acquired) !== 1) throw new Error('Another BI embedded worker already owns this database')
  write({ ready: true })
  let tickPending = false
  timer = setInterval(() => {
    if (tickPending || shuttingDown) return
    tickPending = true
    serialize(async () => {
      // Losing the owner connection also loses the MySQL lease: do not keep dispatching.
      try { await lease.ping() } catch { process.exit(1) }
      const config = await refreshManagedConnection()
      if (config.runtimeEnabled === true) {
        await schedulerTick({ dryRun: false, includeSheetSources: false })
      }
      lastSchedulerError = null
    }).catch(() => { lastSchedulerError = '调度执行失败，请检查连接配置与运行记录' })
      .finally(() => { tickPending = false })
  }, 30000)
  const input = readline.createInterface({ input: process.stdin, crlfDelay: Infinity })
  input.on('line', line => {
    let request
    try { request = JSON.parse(line) } catch { return }
    if (request.method === 'shutdown') {
      input.close(); return
    }
    if (shuttingDown) return
    if (queuedWork > 0) {
      write({ id: request.id, error: '飞书执行器正在处理任务，请稍后重试；已提交的任务请先查看运行记录' })
      return
    }
    serialize(() => handle(request.method, request.args || {}))
      .then(result => write({ id: request.id, result }))
      .catch(err => write({ id: request.id, error: publicError(err) }))
  })
  input.on('close', () => shutdown().finally(() => process.exit(0)))
  process.on('SIGTERM', () => shutdown().finally(() => process.exit(0)))
  process.on('SIGINT', () => shutdown().finally(() => process.exit(0)))
} catch {
  console.error('[BI integration] worker boot failed; check metadata connection and worker ownership')
  if (lease) lease.release()
  await closePool().catch(() => {})
  process.exit(1)
}
