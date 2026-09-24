import fs from 'fs'
import path from 'path'
import { spawn } from 'child_process'
import { fileURLToPath } from 'url'
import { CONFIG } from './config.mjs'
import { parseSpreadsheetUrl, readSpreadsheet } from './sheets.mjs'
import { markSheetSourceSuccess } from './sheet-source.mjs'
import { claimSheetSlot, gridFromValues, openQiwei, syncSnapshot, tableSummary } from './sheet-snapshot.mjs'
import { shanghaiWallToMs } from './schedule.mjs'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const PORT = Number(process.env.QIWEI_MYSQL_PORT || 3327)
let tunnel
let conn

function loadEtlEnv() {
  const envPath = path.resolve(__dirname, '../../../etl_migration/.env')
  const out = {}
  for (const line of fs.readFileSync(envPath, 'utf8').split(/\r?\n/)) {
    const trimmed = line.trim()
    if (!trimmed || trimmed.startsWith('#') || !trimmed.includes('=')) continue
    const eq = trimmed.indexOf('=')
    out[trimmed.slice(0, eq).trim()] = trimmed.slice(eq + 1).trim()
  }
  return out
}

function startTunnel() {
  if (process.env.QIWEI_MYSQL_HOST) return Promise.resolve(null)
  if (tunnel) return Promise.resolve(tunnel)
  const script = path.resolve(__dirname, '../../../etl_migration/scripts/08_build_new.py')
  const child = spawn(
    'py',
    [
      '-3',
      '-c',
      [
        'import importlib.util, time',
        'from pathlib import Path',
        `p = Path(${JSON.stringify(script)})`,
        'spec = importlib.util.spec_from_file_location("build_new", p)',
        'mod = importlib.util.module_from_spec(spec)',
        'spec.loader.exec_module(mod)',
        'env = mod.load_env(mod.ENV_PATH)',
        `ssh, listener, stop, db = mod.connect(env, ${PORT})`,
        'db.close()',
        'print("tunnel-ready", flush=True)',
        'time.sleep(86400)'
      ].join('\n')
    ],
    { stdio: ['ignore', 'pipe', 'pipe'] }
  )
  tunnel = child
  return new Promise((resolve, reject) => {
    let buffer = ''
    const timer = setTimeout(() => reject(new Error('mysql tunnel timeout')), 30000)
    child.stdout.on('data', chunk => {
      buffer += chunk.toString()
      if (buffer.includes('tunnel-ready')) {
        clearTimeout(timer)
        resolve(child)
      }
    })
    child.on('exit', code => {
      clearTimeout(timer)
      if (!buffer.includes('tunnel-ready')) reject(new Error(`tunnel exited ${code}`))
    })
  })
}

export async function getQiwei() {
  await startTunnel()
  if (conn) return conn
  const env = loadEtlEnv()
  conn = await openQiwei({
    host: process.env.QIWEI_MYSQL_HOST || '127.0.0.1',
    port: Number(process.env.QIWEI_MYSQL_PORT || PORT),
    user: process.env.QIWEI_MYSQL_USER || env.WRITE_DB_USER,
    password: process.env.QIWEI_MYSQL_PASSWORD || env.WRITE_DB_PASSWORD
  })
  return conn
}

export function slotDateTime(slotMs) {
  const shifted = new Date(Number(slotMs) + 8 * 60 * 60 * 1000)
  const pad = n => String(n).padStart(2, '0')
  return `${shifted.getUTCFullYear()}-${pad(shifted.getUTCMonth() + 1)}-${pad(shifted.getUTCDate())} ${pad(shifted.getUTCHours())}:${pad(shifted.getUTCMinutes())}:00`
}

export function scheduleAt(hour, minute) {
  return JSON.stringify({
    kind: 'daily',
    time: `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`,
    autoRun: true,
    timezone: 'Asia/Shanghai'
  })
}

export async function syncConfiguredSource(source, slotMs) {
  const db = await getQiwei()
  const plannedAt = slotDateTime(slotMs)
  const parsed = parseSpreadsheetUrl(source.spreadsheet_url)
  const claim = await claimSheetSlot(db, {
    source: source.source_name,
    spreadsheet: parsed.token,
    sheet: source.sheet_name,
    plannedAt,
    startedAt: slotDateTime(Date.now()),
    targetTable: source.target_table
  })
  if (claim.skip) return { skipped: true, reason: `slot-${claim.status}`, runId: claim.runId }
  const book = await readSpreadsheet(
    CONFIG.feishuAppId,
    CONFIG.feishuAppSecret,
    source.spreadsheet_url,
    source.sheet_name,
    source.sheet_id
  )
  const acceptedFields = JSON.parse(source.schema_json)
  const grid = gridFromValues(book.values, {
    headerRow: source.header_row,
    dataStartRow: source.data_start_row,
    columnRange: source.column_range,
    acceptedFields
  })
  const result = await syncSnapshot(db, {
    runId: claim.runId,
    source: source.source_name,
    spreadsheetToken: book.spreadsheetToken,
    sheetTitle: source.sheet_name,
    targetTable: source.target_table,
    columns: grid.columns,
    rows: grid.rows,
    plannedAt
  })
  await markSheetSourceSuccess(source.id)
  const summary = await tableSummary(db, source.target_table, '销售日期')
  return { skipped: false, ...result, summary }
}

export function futureSlot(minutesAhead) {
  const wall = new Date(Date.now() + 8 * 60 * 60 * 1000 + minutesAhead * 60 * 1000)
  const hour = wall.getUTCHours()
  const minute = wall.getUTCMinutes()
  const slot = shanghaiWallToMs(wall.getUTCFullYear(), wall.getUTCMonth() + 1, wall.getUTCDate(), hour, minute)
  return { hour, minute, slot }
}
