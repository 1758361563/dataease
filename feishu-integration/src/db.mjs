import { execFileSync, spawnSync } from 'child_process'
import mysql from 'mysql2/promise'
import mysqlCore from 'mysql2'
import { CONFIG } from './config.mjs'

let pool
let transport = null

function resolveHost() {
  if (CONFIG.mysql.host) return CONFIG.mysql.host
  try {
    const ip = execFileSync(
      'docker',
      ['inspect', '-f', '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}', CONFIG.mysql.dockerContainer],
      { encoding: 'utf8' }
    ).trim()
    if (ip) return ip
  } catch {
    /* fall through */
  }
  return '127.0.0.1'
}

function mysqlPassword() {
  if (CONFIG.mysql.password) return CONFIG.mysql.password
  // Same default as BI-docs/DataEase正式开发/scripts/_local_de_common.sh for local mysql-de.
  return 'Password123@mysql'
}

function sanitizeMysqlText(text) {
  return String(text || '')
    .replace(/-p\S+/g, '-p***')
    .replace(/Using a password on the command line interface can be insecure\.\s*/g, '')
}

function normalizeParams(params) {
  return params.map(value => (typeof value === 'bigint' ? value.toString() : value))
}

function parseMysqlError(text) {
  const raw = sanitizeMysqlText(text)
  if (/ERROR 1062/.test(raw)) {
    const err = new Error(raw.trim() || 'Duplicate entry')
    err.code = 'ER_DUP_ENTRY'
    err.errno = 1062
    return err
  }
  return new Error(raw.trim() || 'mysql docker-exec failed')
}

function coerceMysqlValue(value) {
  if (value === undefined || value === null) return null
  if (value === '\\N' || value === 'NULL' || value === '') return null
  return value
}

function parseBatchRows(output) {
  const lines = sanitizeMysqlText(output)
    .replace(/\r/g, '')
    .split('\n')
    .map(line => line.trimEnd())
    .filter(line => line.length)
  if (!lines.length) return []
  const header = lines[0].split('\t')
  return lines.slice(1).map(line => {
    const cols = line.split('\t')
    const row = {}
    header.forEach((name, index) => {
      row[name] = coerceMysqlValue(cols[index])
    })
    return row
  })
}

function dockerExecSql(sql) {
  const res = spawnSync(
    'docker',
    [
      'exec',
      CONFIG.mysql.dockerContainer,
      'mysql',
      '-u',
      CONFIG.mysql.user,
      `-p${mysqlPassword()}`,
      '--default-character-set=utf8mb4',
      '--batch',
      '--raw',
      CONFIG.mysql.database,
      '-e',
      sql
    ],
    { encoding: 'utf8', maxBuffer: 10 * 1024 * 1024, windowsHide: true }
  )
  if (res.status !== 0) {
    throw parseMysqlError(`${res.stderr || ''}\n${res.stdout || ''}\n${res.error?.message || ''}`)
  }
  return res.stdout || ''
}

function preferDockerExec() {
  if (process.env.DE_MYSQL_TRANSPORT === 'tcp') return false
  if (process.env.DE_MYSQL_TRANSPORT === 'docker-exec') return true
  if (CONFIG.mysql.host) return false
  return process.platform === 'win32'
}

async function pingPool(candidate) {
  const conn = await candidate.getConnection()
  try {
    await conn.ping()
  } finally {
    conn.release()
  }
}

export function getMysqlTransport() {
  return transport
}

export async function getPool() {
  if (transport === 'docker-exec') {
    throw new Error('mysql TCP pool is not available; use exec/query docker-exec transport')
  }
  if (pool) return pool
  const candidate = mysql.createPool({
    host: resolveHost(),
    port: CONFIG.mysql.port,
    user: CONFIG.mysql.user,
    password: mysqlPassword(),
    database: CONFIG.mysql.database,
    waitForConnections: true,
    connectionLimit: 5,
    charset: 'utf8mb4',
    connectTimeout: 2000
  })
  try {
    await pingPool(candidate)
    pool = candidate
    transport = 'tcp'
    return pool
  } catch (err) {
    await candidate.end().catch(() => {})
    transport = 'docker-exec'
    throw err
  }
}

async function ensureTransport() {
  if (transport) return transport
  if (preferDockerExec()) {
    transport = 'docker-exec'
    return transport
  }
  try {
    await getPool()
    return transport
  } catch {
    transport = 'docker-exec'
    return transport
  }
}

export async function closePool() {
  if (pool) {
    await pool.end().catch(() => {})
    pool = null
  }
  transport = null
}

export function nextId() {
  return (BigInt(Date.now()) << 22n) | BigInt(Math.floor(Math.random() * 0x3fffff))
}

export async function exec(sql, params = []) {
  const mode = await ensureTransport()
  if (mode === 'docker-exec') {
    const formatted = params.length ? mysqlCore.format(sql, normalizeParams(params)) : sql
    dockerExecSql(formatted)
    return { affectedRows: 0 }
  }
  const p = await getPool()
  if (!params.length) {
    const [result] = await p.query(sql)
    return result
  }
  const [result] = await p.execute(sql, params)
  return result
}

export async function query(sql, params = []) {
  const mode = await ensureTransport()
  if (mode === 'docker-exec') {
    const formatted = params.length ? mysqlCore.format(sql, normalizeParams(params)) : sql
    return parseBatchRows(dockerExecSql(formatted))
  }
  const p = await getPool()
  const [rows] = await p.execute(sql, params)
  return rows
}
