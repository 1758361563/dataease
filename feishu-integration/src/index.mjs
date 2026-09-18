import { startServer } from './server.mjs'
import { closeBrowser } from './session.mjs'
import { startScheduler } from './scheduler.mjs'
import { ensureExecutionModeSchema, ensureIdentityStatusSchema } from './store.mjs'
import { CONFIG } from './config.mjs'

await ensureExecutionModeSchema()
await ensureIdentityStatusSchema()
const server = await startServer()
const timer = startScheduler()
console.log(
  `[feishu-shot] scheduler started interval=${CONFIG.schedulerIntervalMs}ms dryRun=${CONFIG.dryRun}`
)

const shutdown = async () => {
  clearInterval(timer)
  server.close()
  await closeBrowser()
  process.exit(0)
}
process.on('SIGINT', shutdown)
process.on('SIGTERM', shutdown)
