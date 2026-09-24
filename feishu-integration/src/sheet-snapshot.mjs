import mysql from 'mysql2/promise'

const DB = 'qiwei_new_bi'

export function gridFromValues(values) {
  const matrix = trimUsed(values)
  if (!matrix.length) throw new Error('工作表没有数据')
  const headers = matrix[0].map(cell => (cell === null || cell === undefined ? '' : String(cell).trim()))
  if (headers.some(name => !name)) throw new Error('表头存在空列，停止同步')
  const seen = new Set()
  for (const name of headers) {
    if (seen.has(name)) throw new Error(`字段名重复，停止同步: ${name}`)
    seen.add(name)
  }
  const rows = []
  for (const raw of matrix.slice(1)) {
    const padded = headers.map((_, index) => raw[index])
    if (padded.every(isBlank)) continue
    const row = {}
    headers.forEach((name, index) => {
      row[name] = normalizeCell(name, padded[index])
    })
    rows.push(row)
  }
  if (!rows.length) throw new Error('没有有效数据行，拒绝用空表切换')
  const columns = headers.map(name => ({
    name,
    sqlType: columnType(name, rows)
  }))
  return { headers, columns, rows }
}

export async function syncSnapshot(conn, spec) {
  const plannedAt = spec.plannedAt || nowText()
  const startedAt = nowText()
  const runId = await beginRun(conn, {
    source: spec.source,
    spreadsheet: spec.spreadsheetToken,
    sheet: spec.sheetTitle,
    plannedAt,
    startedAt,
    targetTable: spec.targetTable
  })
  const staging = `${spec.targetTable}_new`
  const previous = `${spec.targetTable}_old`
  try {
    const formalExists = await tableExists(conn, spec.targetTable)
    if (formalExists) {
      const current = await formalColumns(conn, spec.targetTable)
      assertSameSchema(current, spec.columns)
    }
    await conn.query(`DROP TABLE IF EXISTS ${qi(DB)}.${qi(staging)}`)
    await conn.query(
      `CREATE TABLE ${qi(DB)}.${qi(staging)} (${spec.columns
        .map(column => `${qi(column.name)} ${column.sqlType} NULL`)
        .join(', ')}) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`
    )
    await insertRows(conn, staging, spec.columns, spec.rows)
    const loaded = await countRows(conn, staging)
    if (loaded !== spec.rows.length) {
      throw new Error(`写入行数 ${loaded} 与读取行数 ${spec.rows.length} 不一致`)
    }
    if (spec.failBeforeSwap) {
      throw new Error(spec.failBeforeSwap)
    }
    if (formalExists) {
      await conn.query(`DROP TABLE IF EXISTS ${qi(DB)}.${qi(previous)}`)
      await conn.query(
        `RENAME TABLE ${qi(DB)}.${qi(spec.targetTable)} TO ${qi(DB)}.${qi(previous)}, ${qi(DB)}.${qi(staging)} TO ${qi(DB)}.${qi(spec.targetTable)}`
      )
    } else {
      await conn.query(`RENAME TABLE ${qi(DB)}.${qi(staging)} TO ${qi(DB)}.${qi(spec.targetTable)}`)
    }
    const finishedAt = nowText()
    await finishRun(conn, runId, {
      status: 'success',
      finishedAt,
      rows: spec.rows.length,
      columns: spec.columns.length,
      errorSummary: null,
      latestSuccess: finishedAt
    })
    return { runId, rows: spec.rows.length, columns: spec.columns.length, replaced: formalExists }
  } catch (error) {
    await conn.query(`DROP TABLE IF EXISTS ${qi(DB)}.${qi(staging)}`).catch(() => {})
    const latestSuccess = await latestSuccessAt(conn, spec.targetTable)
    await finishRun(conn, runId, {
      status: 'failed',
      finishedAt: nowText(),
      rows: spec.rows ? spec.rows.length : null,
      columns: spec.columns ? spec.columns.length : null,
      errorSummary: safeError(error),
      latestSuccess
    }).catch(() => {})
    throw error
  }
}

export async function openQiwei(config) {
  return mysql.createConnection({
    host: config.host,
    port: config.port,
    user: config.user,
    password: config.password,
    database: DB,
    charset: 'utf8mb4',
    dateStrings: true,
    connectTimeout: 30000
  })
}

export async function tableSummary(conn, table, dateColumn) {
  const [countRow] = await conn.query(`SELECT COUNT(*) AS rows FROM ${qi(DB)}.${qi(table)}`)
  const [cols] = await conn.query(
    `SELECT COLUMN_NAME AS name, DATA_TYPE AS dataType
     FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
     ORDER BY ORDINAL_POSITION`,
    [DB, table]
  )
  let dateMin = null
  let dateMax = null
  let sentinel2099 = null
  if (dateColumn && cols.some(column => column.name === dateColumn)) {
    const [span] = await conn.query(
      `SELECT DATE_FORMAT(MIN(${qi(dateColumn)}), '%Y-%m-%d') AS dateMin,
              DATE_FORMAT(MAX(${qi(dateColumn)}), '%Y-%m-%d') AS dateMax,
              SUM(${qi(dateColumn)} = '2099-01-01') AS sentinel2099
       FROM ${qi(DB)}.${qi(table)}`
    )
    dateMin = span[0].dateMin
    dateMax = span[0].dateMax
    sentinel2099 = Number(span[0].sentinel2099 || 0)
  }
  return {
    rows: Number(countRow[0].rows),
    columns: cols.map(column => column.name),
    types: cols.map(column => column.dataType),
    dateMin,
    dateMax,
    sentinel2099
  }
}

function trimUsed(values) {
  let lastRow = -1
  let lastCol = -1
  values.forEach((row, rowIndex) => {
    ;(row || []).forEach((cell, colIndex) => {
      if (!isBlank(cell)) {
        lastRow = Math.max(lastRow, rowIndex)
        lastCol = Math.max(lastCol, colIndex)
      }
    })
  })
  if (lastRow < 0 || lastCol < 0) return []
  return values.slice(0, lastRow + 1).map(row => (row || []).slice(0, lastCol + 1))
}

function isBlank(value) {
  return value === null || value === undefined || (typeof value === 'string' && value.trim() === '')
}

function normalizeCell(name, value) {
  if (isBlank(value)) return null
  const text = typeof value === 'string' ? value.trim().replace(/,/g, '') : value
  if (name === '销售日期') return toDate(text)
  if (typeof text === 'number') return toDecimalString(text)
  if (typeof text === 'string' && /^-?\d+(\.\d+)?$/.test(text)) return toDecimalString(text)
  return String(text)
}

function toDate(value) {
  const text = String(value).trim()
  const match = text.match(/^(\d{4})[/-](\d{1,2})[/-](\d{1,2})$/)
  if (!match) throw new Error('销售日期格式不稳定，停止同步')
  const month = match[2].padStart(2, '0')
  const day = match[3].padStart(2, '0')
  return `${match[1]}-${month}-${day}`
}

function toDecimalString(value) {
  const text = String(value)
  if (!/^-?\d+(\.\d+)?$/.test(text)) throw new Error('数字字段不稳定，停止同步')
  const fraction = text.includes('.') ? text.split('.')[1] : ''
  if (fraction.length > 4) throw new Error('小数位超过 4 位，停止同步')
  return text
}

function columnType(name, rows) {
  const values = rows.map(row => row[name]).filter(value => value !== null)
  if (name === '销售日期' && values.every(value => /^\d{4}-\d{2}-\d{2}$/.test(String(value)))) return 'DATE'
  if (values.every(value => /^-?\d+(\.\d+)?$/.test(String(value)))) return 'DECIMAL(18,4)'
  return 'VARCHAR(255)'
}

function assertSameSchema(current, incoming) {
  const left = current.map(column => `${column.name}:${column.dataType}`).join('|')
  const right = incoming
    .map(column => `${column.name}:${column.sqlType.startsWith('DECIMAL') ? 'decimal' : column.sqlType.toLowerCase()}`)
    .join('|')
  if (left !== right) {
    throw new Error('字段结构与正式表不一致，停止切换')
  }
}

async function formalColumns(conn, table) {
  const [rows] = await conn.query(
    `SELECT COLUMN_NAME AS name, DATA_TYPE AS dataType
     FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
     ORDER BY ORDINAL_POSITION`,
    [DB, table]
  )
  return rows
}

async function tableExists(conn, table) {
  const [rows] = await conn.query(
    `SELECT 1 AS ok FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?`,
    [DB, table]
  )
  return rows.length > 0
}

async function countRows(conn, table) {
  const [rows] = await conn.query(`SELECT COUNT(*) AS rows FROM ${qi(DB)}.${qi(table)}`)
  return Number(rows[0].rows)
}

async function insertRows(conn, table, columns, rows) {
  const names = columns.map(column => qi(column.name)).join(', ')
  const marks = columns.map(() => '?').join(', ')
  const sql = `INSERT INTO ${qi(DB)}.${qi(table)} (${names}) VALUES (${marks})`
  for (const row of rows) {
    await conn.execute(sql, columns.map(column => row[column.name]))
  }
}

async function beginRun(conn, run) {
  await conn.query(`
    CREATE TABLE IF NOT EXISTS ${qi(DB)}.\`feishu_sheet_run\` (
      id BIGINT NOT NULL AUTO_INCREMENT,
      source VARCHAR(128) NOT NULL,
      spreadsheet VARCHAR(128) NOT NULL,
      sheet VARCHAR(128) NOT NULL,
      planned_at DATETIME NOT NULL,
      started_at DATETIME NOT NULL,
      finished_at DATETIME NULL,
      status VARCHAR(16) NOT NULL,
      \`rows\` BIGINT NULL,
      \`columns\` INT NULL,
      target_table VARCHAR(128) NOT NULL,
      error_summary VARCHAR(512) NULL,
      latest_success DATETIME NULL,
      PRIMARY KEY (id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  `)
  const [result] = await conn.execute(
    `INSERT INTO ${qi(DB)}.\`feishu_sheet_run\`
      (source, spreadsheet, sheet, planned_at, started_at, status, target_table)
     VALUES (?, ?, ?, ?, ?, 'running', ?)`,
    [run.source, run.spreadsheet, run.sheet, run.plannedAt, run.startedAt, run.targetTable]
  )
  return result.insertId
}

async function finishRun(conn, runId, patch) {
  await conn.execute(
    `UPDATE ${qi(DB)}.\`feishu_sheet_run\`
        SET status = ?, finished_at = ?, \`rows\` = ?, \`columns\` = ?, error_summary = ?, latest_success = ?
      WHERE id = ? AND status = 'running'`,
    [patch.status, patch.finishedAt, patch.rows, patch.columns, patch.errorSummary, patch.latestSuccess, runId]
  )
}

async function latestSuccessAt(conn, targetTable) {
  const [rows] = await conn.execute(
    `SELECT DATE_FORMAT(MAX(latest_success), '%Y-%m-%d %H:%i:%s') AS latest_success
     FROM ${qi(DB)}.\`feishu_sheet_run\`
     WHERE target_table = ? AND status = 'success'`,
    [targetTable]
  )
  return rows[0] ? rows[0].latest_success : null
}

function nowText() {
  return new Intl.DateTimeFormat('sv-SE', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23'
  })
    .format(new Date())
    .replace(' ', ' ')
}

function safeError(error) {
  return String(error && error.message ? error.message : error)
    .replace(/Bearer\s+\S+/gi, 'Bearer ***')
    .replace(/app_secret["']?\s*[:=]\s*\S+/gi, 'app_secret=***')
    .slice(0, 500)
}

function qi(name) {
  return '`' + String(name).replace(/`/g, '``') + '`'
}
