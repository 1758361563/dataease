import fs from 'fs'
import path from 'path'
import { LOG_DIR } from './config.mjs'

const SECRET_ASSIGN = /((?:app_secret|password|passwd|token|authorization|secret)\s*[=:]\s*['"]?)(\S+)/gi

export function redactSecrets(text) {
  return String(text || '')
    .replace(SECRET_ASSIGN, '$1(redacted)')
    .replace(/Bearer\s+\S+/gi, 'Bearer (redacted)')
    .replace(/\bt-[A-Za-z0-9_-]{8,}\b/g, 'token=(redacted)')
}

export function recordServiceEvent(event = {}) {
  const row = {
    at: new Date().toISOString(),
    area: String(event.area || 'service'),
    message: redactSecrets(event.message || '').replace(/\s+/g, ' ').slice(0, 500)
  }
  if (event.runId != null && event.runId !== '') row.runId = String(event.runId)
  if (event.code) row.code = String(event.code)
  fs.mkdirSync(LOG_DIR, { recursive: true })
  fs.appendFileSync(path.join(LOG_DIR, 'service-events.jsonl'), `${JSON.stringify(row)}\n`)
  return row
}
