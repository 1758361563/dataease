import path from 'path'
import { fileURLToPath } from 'url'
import { CONFIG } from './config.mjs'
import { closePool, exec, nextId } from './db.mjs'
import { listSpreadsheetSheets, readSpreadsheet } from './sheets.mjs'
import {
  ensureDataEndRowColumn,
  getSheetSourceByName
} from './sheet-source.mjs'
import { closeQiwei, syncConfiguredSource } from './sheet-sync.mjs'
import { gridFromValues } from './sheet-snapshot.mjs'

function columnName(index) {
  let n = index
  let name = ''
  while (n > 0) {
    const rem = (n - 1) % 26
    name = String.fromCharCode(65 + rem) + name
    n = Math.floor((n - 1) / 26)
  }
  return name
}

function isBlank(value) {
  return value === null || value === undefined || (typeof value === 'string' && value.trim() === '')
}

export function columnProfile(values, headerRow) {
  let lastCol = -1
  values.forEach(row => {
    ;(row || []).forEach((cell, index) => {
      if (!isBlank(cell)) lastCol = Math.max(lastCol, index)
    })
  })
  if (lastCol < 0) return []
  const header = values[Number(headerRow) - 1] || []
  const columns = []
  for (let index = 0; index <= lastCol; index++) {
    let nonEmpty = 0
    for (const row of values) {
      if (!isBlank((row || [])[index])) nonEmpty += 1
    }
    const raw = header[index]
    columns.push({
      letter: columnName(index + 1),
      header: raw === null || raw === undefined ? '' : String(raw).trim(),
      nonEmpty
    })
  }
  return columns
}

export function previewFromValues(values, spec) {
  const grid = gridFromValues(values, {
    headerRow: spec.headerRow,
    dataStartRow: spec.dataStartRow,
    dataEndRow: spec.dataEndRow || undefined,
    columnRange: spec.columnRange
  })
  const nulls = {}
  for (const column of grid.columns) {
    nulls[column.name] = grid.rows.filter(row => row[column.name] === null).length
  }
  return {
    sheetName: spec.sheetName,
    headers: grid.headers,
    rows: grid.rows.length,
    columns: grid.columns.length,
    sample: grid.rows.slice(0, 5),
    nulls,
    types: grid.columns.map(column => ({ name: column.name, sqlType: column.sqlType }))
  }
}

function scheduleJson(hhmm) {
  if (!hhmm) return null
  return JSON.stringify({
    kind: 'daily',
    time: hhmm,
    autoRun: true,
    timezone: 'Asia/Shanghai'
  })
}

async function readStdin() {
  const chunks = []
  for await (const chunk of process.stdin) chunks.push(chunk)
  const text = Buffer.concat(chunks).toString('utf8').trim()
  if (!text) throw new Error('缺少 JSON 指令')
  return JSON.parse(text)
}

async function listSheets(spec) {
  return listSpreadsheetSheets(CONFIG.feishuAppId, CONFIG.feishuAppSecret, spec.url)
}

async function loadSheet(spec) {
  const book = await readSpreadsheet(
    CONFIG.feishuAppId,
    CONFIG.feishuAppSecret,
    spec.url,
    spec.sheetName || '',
    spec.sheetId || ''
  )
  return book
}

async function profileSheet(spec) {
  const book = await loadSheet(spec)
  return {
    sheetId: book.sheet.sheetId,
    sheetName: book.sheet.title,
    columns: columnProfile(book.values, spec.headerRow || 1)
  }
}

async function previewSheet(spec) {
  const book = await loadSheet(spec)
  return previewFromValues(book.values, {
    ...spec,
    sheetName: book.sheet.title
  })
}

async function syncNewSource(spec) {
  await ensureDataEndRowColumn()
  const existing = await getSheetSourceByName(spec.sourceName)
  if (existing) {
    throw new Error(`飞书 source「${spec.sourceName}」已存在，本次不修改`)
  }
  const now = Date.now()
  const id = nextId().toString()
  await exec(
    `INSERT INTO company_feishu_source
      (id, source_name, spreadsheet_url, sheet_id, sheet_name, header_row, data_start_row,
       data_end_row, column_range, target_database, target_table, enabled, schedule_json, schema_json,
       created_at, created_by, updated_at, updated_by)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, 1)`,
    [
      id,
      spec.sourceName,
      spec.spreadsheetUrl,
      spec.sheetId || '',
      spec.sheetName,
      spec.headerRow,
      spec.dataStartRow,
      spec.dataEndRow || null,
      spec.columnRange,
      spec.targetDatabase,
      spec.targetTable,
      spec.enabled ? 1 : 0,
      scheduleJson(spec.schedule),
      JSON.stringify(spec.schema || []),
      now,
      now
    ]
  )
  try {
    const source = await getSheetSourceByName(spec.sourceName)
    if (!source) throw new Error('刚写入的飞书 source 读不回来')
    const report = await syncConfiguredSource(source, Date.now())
    if (report.skipped) throw new Error(report.reason || '同步被跳过')
    return {
      rows: report.rows,
      columns: report.columns,
      targetTable: spec.targetTable
    }
  } catch (error) {
    await exec('DELETE FROM company_feishu_source WHERE id = ?', [id]).catch(() => {})
    throw error
  } finally {
    await closeQiwei()
  }
}

async function dispatch(spec) {
  if (spec.command === 'list') return listSheets(spec)
  if (spec.command === 'profile') return profileSheet(spec)
  if (spec.command === 'preview') return previewSheet(spec)
  if (spec.command === 'sync') return syncNewSource(spec)
  throw new Error(`不认识的指令: ${spec.command || ''}`)
}

function launchedDirectly() {
  const self = path.resolve(fileURLToPath(import.meta.url))
  const arg = process.argv[1] ? path.resolve(process.argv[1]) : ''
  return self === arg
}

if (launchedDirectly()) {
  try {
    const spec = await readStdin()
    const result = await dispatch(spec)
    process.stdout.write(`${JSON.stringify(result)}\n`)
  } catch (error) {
    console.error(String(error && error.message ? error.message : error).slice(0, 300))
    process.exitCode = 1
  } finally {
    await closePool().catch(() => {})
  }
}
