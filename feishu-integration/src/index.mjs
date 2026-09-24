import { startServer } from './server.mjs'
import { closeBrowser } from './session.mjs'
import { startScheduler } from './scheduler.mjs'
import { recordServiceEvent } from './service-log.mjs'
import { ensureDeliveryLaneSchema, ensureExecutionModeSchema, ensureIdentityStatusSchema } from './store.mjs'
import { ensure798Source } from './sheet-source.mjs'
import { CONFIG } from './config.mjs'

let server
let timer
try {
  await ensureExecutionModeSchema()
  await ensureIdentityStatusSchema()
  await ensureDeliveryLaneSchema()
  await ensure798Source()
  server = await startServer()
  timer = startScheduler()
  console.log(
    `[feishu-shot] scheduler started interval=${CONFIG.schedulerIntervalMs}ms dryRun=${CONFIG.dryRun} logDir=${process.env.FEISHU_LOG_DIR || 'log'}`
  )
} catch (err) {
  recordServiceEvent({ area: 'boot', message: err?.message || String(err) })
  console.error('[feishu-shot] boot failed', err?.message || err)
  process.exit(1)
}

const shutdown = async () => {
  clearInterval(timer)
  server.close()
  await closeBrowser()
  process.exit(0)
}
process.on('SIGINT', shutdown)
process.on('SIGTERM', shutdown)
process.on('uncaughtException', err => {
  recordServiceEvent({ area: 'process', message: err?.message || String(err) })
  console.error('[feishu-shot] uncaught', err?.message || err)
})
process.on('unhandledRejection', err => {
  const message = err?.message || String(err)
  recordServiceEvent({ area: 'process', message })
  console.error('[feishu-shot] unhandled rejection', message)
})
