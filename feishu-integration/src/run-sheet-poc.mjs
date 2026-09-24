import fs from 'fs'
import path from 'path'
import { spawn } from 'child_process'
import { CONFIG } from './config.mjs'
import { readSpreadsheet } from './sheets.mjs'
import { gridFromValues, openQiwei, syncSnapshot, tableSummary } from './sheet-snapshot.mjs'

const SOURCE_URL = process.argv[2]
const SHEET = 'Sheet1'
const SOURCE = '798门店手工数据-SS'
const TARGET = '798门店手工数据_SS'
const PORT = 3326
const ETL_ENV = path.resolve(process.cwd(), '../../etl_migration/.env')

if (!SOURCE_URL) {
  console.error('missing spreadsheet url')
  process.exit(1)
}

function loadEtlEnv() {
  const out = {}
  for (const line of fs.readFileSync(ETL_ENV, 'utf8').split(/\r?\n/)) {
    const trimmed = line.trim()
    if (!trimmed || trimmed.startsWith('#') || !trimmed.includes('=')) continue
    const eq = trimmed.indexOf('=')
    out[trimmed.slice(0, eq).trim()] = trimmed.slice(eq + 1).trim()
  }
  return out
}

function startTunnel() {
  const child = spawn(
    'py',
    [
      '-3',
      '-c',
      [
        'import importlib.util, time',
        'from pathlib import Path',
        'p = Path(r"d:/Coding_project/scent_library/test/etl_migration/scripts/08_build_new.py")',
        'spec = importlib.util.spec_from_file_location("build_new", p)',
        'mod = importlib.util.module_from_spec(spec)',
        'spec.loader.exec_module(mod)',
        'env = mod.load_env(mod.ENV_PATH)',
        `ssh, listener, stop, conn = mod.connect(env, ${PORT})`,
        'conn.close()',
        'print("tunnel-ready", flush=True)',
        'time.sleep(600)'
      ].join('\n')
    ],
    { stdio: ['ignore', 'pipe', 'pipe'] }
  )
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
    child.stderr.on('data', chunk => {
      const text = chunk.toString()
      if (/password|secret/i.test(text)) return
      process.stderr.write(text)
    })
    child.on('exit', code => {
      clearTimeout(timer)
      reject(new Error(`tunnel exited ${code}`))
    })
  })
}

function simulateNext(grid) {
  const rows = grid.rows.map(row => ({ ...row }))
  rows.pop()
  rows[0] = { ...rows[0], 日销售目标: '999999.01' }
  const blank = Object.fromEntries(grid.headers.map(name => [name, null]))
  rows.push({ ...blank, 销售日期: '2099-01-01', 日销售目标: '1' })
  return { ...grid, rows }
}

async function main() {
  const tunnel = await startTunnel()
  const env = loadEtlEnv()
  const conn = await openQiwei({
    host: '127.0.0.1',
    port: PORT,
    user: env.WRITE_DB_USER,
    password: env.WRITE_DB_PASSWORD
  })
  const report = {}
  try {
    const book = await readSpreadsheet(CONFIG.feishuAppId, CONFIG.feishuAppSecret, SOURCE_URL, SHEET)
    const grid = gridFromValues(book.values)
    report.read = {
      title: book.title,
      sheet: book.sheet.title,
      sheetCount: book.sheets.length,
      otherSheets: book.sheets.filter(sheet => sheet.title !== SHEET).map(sheet => sheet.title),
      rows: grid.rows.length,
      columns: grid.columns.map(column => `${column.name}:${column.sqlType}`)
    }
    await syncSnapshot(conn, {
      source: SOURCE,
      spreadsheetToken: book.spreadsheetToken,
      sheetTitle: SHEET,
      targetTable: TARGET,
      columns: grid.columns,
      rows: grid.rows
    })
    report.first = await tableSummary(conn, TARGET, '销售日期')

    const mutated = simulateNext(grid)
    await syncSnapshot(conn, {
      source: SOURCE,
      spreadsheetToken: book.spreadsheetToken,
      sheetTitle: SHEET,
      targetTable: TARGET,
      columns: mutated.columns,
      rows: mutated.rows
    })
    report.second = await tableSummary(conn, TARGET, '销售日期')

    let failed = false
    try {
      const broken = {
        ...grid,
        columns: grid.columns.map((column, index) => (index === 1 ? { ...column, name: '日销售目标_已改名' } : column)),
        rows: grid.rows.map(row => {
          const copy = { ...row }
          copy['日销售目标_已改名'] = copy['日销售目标']
          return copy
        })
      }
      await syncSnapshot(conn, {
        source: SOURCE,
        spreadsheetToken: book.spreadsheetToken,
        sheetTitle: SHEET,
        targetTable: TARGET,
        columns: broken.columns,
        rows: broken.rows
      })
    } catch (error) {
      failed = true
      report.failureError = String(error.message || error).slice(0, 180)
    }
    report.failureBlocked = failed
    report.afterFailure = await tableSummary(conn, TARGET, '销售日期')

    await syncSnapshot(conn, {
      source: SOURCE,
      spreadsheetToken: book.spreadsheetToken,
      sheetTitle: SHEET,
      targetTable: TARGET,
      columns: grid.columns,
      rows: grid.rows
    })
    report.restored = await tableSummary(conn, TARGET, '销售日期')
    const [runs] = await conn.query(
      `SELECT status, \`rows\`, \`columns\`, target_table,
              error_summary IS NOT NULL AS has_error,
              latest_success IS NOT NULL AS has_latest_success
       FROM \`qiwei_new_bi\`.\`feishu_sheet_run\`
       WHERE target_table = ?
       ORDER BY id DESC
       LIMIT 4`,
      [TARGET]
    )
    report.runs = runs
    console.log(JSON.stringify(report, null, 2))
  } finally {
    await conn.end().catch(() => {})
    tunnel.kill()
  }
}

main().catch(error => {
  console.error(String(error && error.message ? error.message : error).slice(0, 300))
  process.exit(1)
})
