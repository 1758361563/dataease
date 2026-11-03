// Read-only, bounded spreadsheet RPC. Database publication is owned by the BI task executor.
import { query } from './db.mjs'
import { decryptConnection } from './managed-connection.mjs'

export function spreadsheetLocation(input) {
  let url
  try { url = new URL(input) } catch { throw new Error('请输入有效的飞书电子表格地址') }
  const match = /^\/sheets\/([a-zA-Z0-9]+)\/?$/.exec(url.pathname)
  if (url.protocol !== 'https:' || url.port || url.username || url.password ||
      !/(^|\.)feishu\.cn$/.test(url.hostname) || !match) {
    throw new Error('请使用 https://…feishu.cn/sheets/… 电子表格地址')
  }
  return { token: match[1], sheetHint: url.searchParams.get('sheet') || '' }
}
export function columnNumber(value) {
  if (!/^[A-Z]{1,3}$/.test(value)) throw new Error('列范围应为 A:M 格式')
  return [...value].reduce((n, c) => n * 26 + c.charCodeAt(0) - 64, 0)
}
export function columnName(index) {
  let name = ''
  for (let n = index; n > 0; n = Math.floor((n - 1) / 26)) name = String.fromCharCode(65 + (n - 1) % 26) + name
  return name
}
export function rangeColumns(range, count) {
  const parts = range ? String(range).trim().toUpperCase().split(':') : ['A', columnName(count)]
  if (parts.length !== 2) throw new Error('列范围应为 A:M 格式')
  const start = columnNumber(parts[0]), end = columnNumber(parts[1])
  if (end < start || end > count || end - start + 1 > 256) throw new Error('列范围超出工作表或超过 256 列')
  return { start, end, first: parts[0], last: parts[1] }
}
async function jsonResponse(response) {
  const reader = response.body.getReader()
  const chunks = []; let bytes = 0
  try {
    while (true) {
      const { value, done } = await reader.read(); if (done) break
      bytes += value.length
      if (bytes > 4 * 1024 * 1024) throw new Error('飞书返回内容过大，请缩小读取范围')
      chunks.push(Buffer.from(value))
    }
    const body = JSON.parse(Buffer.concat(chunks).toString('utf8'))
    if (!response.ok || body.code !== 0) throw new Error(`飞书接口失败，错误码 ${body.code ?? response.status}；请检查表格授权`)
    return body
  } finally { await reader.cancel().catch(() => {}); reader.releaseLock() }
}
export async function managedSheets(method, args) {
  try {
    const location = spreadsheetLocation(args.url)
    const rows = await query('SELECT config_json,secret_cipher FROM company_integration_connection WHERE provider=? AND connection_key=?', ['FEISHU', args.connectionKey])
    if (rows.length !== 1) throw new Error('飞书应用连接不存在')
    const row = rows[0], config = typeof row.config_json === 'string' ? JSON.parse(row.config_json) : row.config_json
    if (config.runtimeEnabled !== true) throw new Error('请启用所选飞书应用连接')
    const secrets = decryptConnection(row.secret_cipher, process.env.COMPANY_BI_INTEGRATION_KEY, 'FEISHU', args.connectionKey)
    const auth = await jsonResponse(await fetch('https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ app_id: config.appId, app_secret: secrets.appSecret }), signal: AbortSignal.timeout(15000)
    }))
    const api = async path => (await jsonResponse(await fetch('https://open.feishu.cn/open-apis' + path, {
      headers: { Authorization: `Bearer ${auth.tenant_access_token}` }, signal: AbortSignal.timeout(20000)
    }))).data
    const base = '/sheets/v2/spreadsheets/' + location.token
    const meta = await api(base + '/metainfo')
    const sheets = (meta.sheets || []).map(s => ({ sheetId: String(s.sheetId), title: s.title, rowCount: s.rowCount, columnCount: s.columnCount }))
    if (method === 'sheets.list') return { title: meta.properties?.title || '', sheetHint: location.sheetHint, sheets }
    if (method !== 'sheets.read') throw new Error('不支持的表格操作')
    const sheet = sheets.find(s => s.sheetId === args.sheetId)
    if (!sheet) throw new Error('所选工作表已不存在，请重新选择')
    const columns = rangeColumns(args.columnRange, sheet.columnCount)
    const start = Number(args.startRow), end = Number(args.endRow)
    if (!Number.isInteger(start) || !Number.isInteger(end) || start < 1 || end < start || end - start >= 100) throw new Error('单次读取范围应在 1–100 行内')
    if (start > sheet.rowCount) return { values: [], sheet, columns }
    const range = `${sheet.sheetId}!${columns.first}${start}:${columns.last}${Math.min(end, sheet.rowCount)}`
    const data = await api(base + '/values/' + encodeURIComponent(range) + '?valueRenderOption=UnformattedValue&dateTimeRenderOption=FormattedString')
    const values = data.valueRange?.values || []
    for (const row of values) for (const value of row) {
      if (typeof value === 'number' && (!Number.isFinite(value) || Number.isInteger(value) && !Number.isSafeInteger(value))) throw new Error('飞书数字超出安全精度，请将长编号在源表中存为文本')
    }
    return { values, sheet, columns }
  } catch (error) {
    // Only our operation diagnostics cross RPC; never return upstream response bodies or credentials.
    if (error instanceof TypeError || error.name === 'TimeoutError' || error.name === 'AbortError') throw Object.assign(new Error('飞书连接超时或网络不可用'), { sheetError: true })
    throw Object.assign(new Error(error.message?.startsWith('飞书') || error.message?.startsWith('请') || error.message?.startsWith('所选') || error.message?.startsWith('列范围') || error.message?.startsWith('单次') ? error.message : '飞书表格读取失败，请检查连接和读取范围'), { sheetError: true })
  }
}
