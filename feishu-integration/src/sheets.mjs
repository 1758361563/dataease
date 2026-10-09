import { getTenantAccessToken } from './feishu.mjs'

const FEISHU_API = 'https://open.feishu.cn/open-apis'

export function parseSpreadsheetUrl(input) {
  const raw = String(input || '').trim()
  let url
  try {
    url = new URL(raw)
  } catch {
    throw new Error('飞书链接不是有效 URL')
  }
  const parts = url.pathname.split('/').filter(Boolean)
  const sheetsAt = parts.indexOf('sheets')
  const wikiAt = parts.indexOf('wiki')
  if (sheetsAt >= 0 && parts[sheetsAt + 1]) {
    return { kind: 'sheets', token: parts[sheetsAt + 1], sheetHint: url.searchParams.get('sheet') || '' }
  }
  if (wikiAt >= 0 && parts[wikiAt + 1]) {
    return { kind: 'wiki', token: parts[wikiAt + 1], sheetHint: url.searchParams.get('sheet') || '' }
  }
  throw new Error('只接受 /sheets/ 或 /wiki/ 链接')
}

async function api(token, path) {
  const res = await fetch(`${FEISHU_API}${path}`, {
    headers: { Authorization: `Bearer ${token}` }
  })
  const text = await res.text()
  let json
  try {
    json = JSON.parse(text)
  } catch {
    json = { code: -1, msg: text.slice(0, 180) }
  }
  if (res.status !== 200 || json.code !== 0) {
    const err = new Error(`feishu api failed http=${res.status} code=${json.code} msg=${json.msg || ''}`)
    err.http = res.status
    err.feishuCode = json.code
    throw err
  }
  return json.data
}

export async function listSpreadsheetSheets(appId, appSecret, sourceUrl) {
  const parsed = parseSpreadsheetUrl(sourceUrl)
  if (parsed.kind === 'wiki') {
    throw new Error('wiki 链接需要先解析资源；请粘贴 /sheets/ 链接')
  }
  const auth = await getTenantAccessToken(appId, appSecret)
  const meta = await api(auth.token, `/sheets/v2/spreadsheets/${encodeURIComponent(parsed.token)}/metainfo`)
  return {
    title: (meta.properties && meta.properties.title) || '',
    sheetHint: parsed.sheetHint,
    sheets: (meta.sheets || []).map(sheet => ({
      sheetId: sheet.sheetId,
      title: sheet.title,
      rowCount: sheet.rowCount,
      columnCount: sheet.columnCount
    }))
  }
}

export async function readSpreadsheet(appId, appSecret, sourceUrl, sheetTitle, sheetId) {
  const parsed = parseSpreadsheetUrl(sourceUrl)
  if (parsed.kind === 'wiki') {
    throw new Error('wiki 链接需要先解析资源；本表已确认为 /sheets/，不应走 wiki')
  }
  const auth = await getTenantAccessToken(appId, appSecret)
  const meta = await api(auth.token, `/sheets/v2/spreadsheets/${encodeURIComponent(parsed.token)}/metainfo`)
  const sheets = (meta.sheets || []).map(sheet => ({
    sheetId: sheet.sheetId,
    title: sheet.title,
    rowCount: sheet.rowCount,
    columnCount: sheet.columnCount
  }))
  const sheet = sheets.find(item => (sheetId && item.sheetId === sheetId) || item.title === sheetTitle)
  if (!sheet) {
    throw new Error(`工作表不存在: ${sheetTitle}；现有: ${sheets.map(item => item.title).join(', ')}`)
  }
  const endCol = columnName(Math.max(sheet.columnCount || 1, 1))
  const endRow = Math.max(sheet.rowCount || 1, 1)
  const range = `${sheet.sheetId}!A1:${endCol}${endRow}`
  const data = await api(
    auth.token,
    `/sheets/v2/spreadsheets/${encodeURIComponent(parsed.token)}/values/${encodeURIComponent(range)}?valueRenderOption=FormattedValue&dateTimeRenderOption=FormattedString`
  )
  const values = (data.valueRange && data.valueRange.values) || []
  return {
    spreadsheetToken: parsed.token,
    title: (meta.properties && meta.properties.title) || '',
    sheets,
    sheet,
    values
  }
}

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
