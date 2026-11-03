import { test } from 'node:test'
import assert from 'node:assert/strict'
process.env.COMPANY_BI_EMBEDDED_WORKER = 'true'
const { spreadsheetLocation, rangeColumns, columnName } = await import('./managed-sheets.mjs')
test('sheet URLs are restricted to authenticated Feishu Sheets resources', () => {
  assert.equal(spreadsheetLocation('https://tenant.feishu.cn/sheets/Ab123?sheet=xy').sheetHint, 'xy')
  for (const url of ['http://tenant.feishu.cn/sheets/a', 'https://evil.test/sheets/a', 'https://tenant.feishu.cn.evil.test/sheets/a', 'https://tenant.feishu.cn/wiki/a', 'https://user@tenant.feishu.cn/sheets/a']) assert.throws(() => spreadsheetLocation(url))
})
test('column ranges are bounded and validated', () => {
  assert.equal(columnName(27), 'AA')
  assert.deepEqual(rangeColumns('B:D', 5), { start: 2, end: 4, first: 'B', last: 'D' })
  for (const range of ['D:B', 'A:ZZ', 'A', 'A1:B2']) assert.throws(() => rangeColumns(range, 500))
})
