import fs from 'fs'
import path from 'path'
import { fileURLToPath } from 'url'
import { exec, nextId, query } from './db.mjs'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const SCHEMA = path.resolve(
  __dirname,
  '../../core/core-backend/src/main/resources/db/company/company_feishu_source.sql'
)

export const SOURCE_798 = {
  sourceName: '798门店手工数据-SS',
  spreadsheetUrl: 'https://lxlae0k7e9.feishu.cn/sheets/V7H0sXdGMhlgBNtcUugcjXngnHd',
  sheetId: '747268',
  sheetName: 'Sheet1',
  headerRow: 1,
  dataStartRow: 2,
  columnRange: 'A:M',
  targetDatabase: 'qiwei_new_bi',
  targetTable: '798门店手工数据_SS',
  schema: [
    ['销售日期', 'DATE'],
    ['日销售目标', 'DECIMAL(18,4)'],
    ['整店进店客流', 'DECIMAL(18,4)'],
    ['线香品类的纯新客数', 'DECIMAL(18,4)'],
    ['新会员目标', 'DECIMAL(18,4)'],
    ['新会员实际', 'DECIMAL(18,4)'],
    ['购买且知道品牌的顾客数量', 'DECIMAL(18,4)'],
    ['外国购买客人数量', 'DECIMAL(18,4)'],
    ['复购人次', 'DECIMAL(18,4)'],
    ['复购金额', 'DECIMAL(18,4)'],
    ['会员消费金额', 'DECIMAL(18,4)'],
    ['企微绑定人数', 'DECIMAL(18,4)'],
    ['私域销售金额', 'DECIMAL(18,4)']
  ].map(([name, sqlType]) => ({ name, sqlType }))
}

export const DAILY_0100 = JSON.stringify({
  kind: 'daily',
  time: '01:00',
  autoRun: true,
  timezone: 'Asia/Shanghai'
})

export async function ensureSheetSourceSchema() {
  const sql = fs.readFileSync(SCHEMA, 'utf8')
  await exec(sql)
}

export async function listEnabledSheetSources() {
  return query(`SELECT * FROM company_feishu_source WHERE enabled = 1 ORDER BY id`)
}

export async function getSheetSourceByName(name) {
  const rows = await query(`SELECT * FROM company_feishu_source WHERE source_name = ?`, [name])
  return rows[0] || null
}

export async function ensure798Source() {
  await ensureSheetSourceSchema()
  const existing = await getSheetSourceByName(SOURCE_798.sourceName)
  if (existing) return existing
  const now = Date.now()
  const id = nextId().toString()
  await exec(
    `INSERT INTO company_feishu_source
      (id, source_name, spreadsheet_url, sheet_id, sheet_name, header_row, data_start_row,
       column_range, target_database, target_table, enabled, schedule_json, schema_json,
       created_at, created_by, updated_at, updated_by)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, 1, ?, 1)`,
    [
      id,
      SOURCE_798.sourceName,
      SOURCE_798.spreadsheetUrl,
      SOURCE_798.sheetId,
      SOURCE_798.sheetName,
      SOURCE_798.headerRow,
      SOURCE_798.dataStartRow,
      SOURCE_798.columnRange,
      SOURCE_798.targetDatabase,
      SOURCE_798.targetTable,
      DAILY_0100,
      JSON.stringify(SOURCE_798.schema),
      now,
      now
    ]
  )
  return getSheetSourceByName(SOURCE_798.sourceName)
}

export async function updateSheetSourceSchedule(name, { enabled, scheduleJson }) {
  await exec(
    `UPDATE company_feishu_source
        SET enabled = ?, schedule_json = ?, updated_at = ?
      WHERE source_name = ?`,
    [enabled ? 1 : 0, scheduleJson, Date.now(), name]
  )
}

export async function markSheetSourceSuccess(id, whenMs = Date.now()) {
  await exec(`UPDATE company_feishu_source SET latest_success_at = ?, updated_at = ? WHERE id = ?`, [
    whenMs,
    whenMs,
    id
  ])
}
