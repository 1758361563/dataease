import { startServer } from './server.mjs'
import { closeBrowser } from './session.mjs'

const server = await startServer()

const shutdown = async () => {
  server.close()
  await closeBrowser()
  process.exit(0)
}
process.on('SIGINT', shutdown)
process.on('SIGTERM', shutdown)
