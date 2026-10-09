const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const ts = require('typescript')
global.window = { devicePixelRatio: 1 }
global.document = { documentElement: { style: {} } }
const { PivotDataSet, EXTRA_FIELD } = require('@antv/s2')
const ExcelJS = require('exceljs')

const moduleCopy = { exports: {} }
new Function(
  'module',
  'exports',
  'require',
  ts.transpileModule(fs.readFileSync(__dirname + '/pivotMeasureRows.ts', 'utf8'), {
    compilerOptions: {
      module: ts.ModuleKind.CommonJS,
      target: ts.ScriptTarget.ES2020,
      esModuleInterop: true
    }
  }).outputText
)(moduleCopy, moduleCopy.exports, id => {
  if (id.includes('chartFieldInstance')) {
    return { resultKey: field => field.dataeaseName }
  }
  if (id.endsWith('/util') || id === '../../../util') {
    return { parseJson: value => value }
  }
  throw new Error(id)
})
const {
  rowQuotaGridMeasureCell,
  rowQuotaGridDataCell,
  rowQuotaGridQuotaHeadCell,
  rowQuotaGridColLeafCell,
  rowQuotaGridColNodeCell
} = moduleCopy.exports

const ds = Object.create(PivotDataSet.prototype)
ds.isCustomMeasuresPosition = () => false
ds.spreadsheet = {}
const out = ds.processDataCfg({
  data: [
    {
      big_channel: '京东',
      channel: '京东',
      month: '2026-03',
      gmv: 100,
      tag_amount: 80,
      discount_rate: 0.8
    }
  ],
  meta: [
    { field: 'gmv', name: 'GMV', formatter: value => `¥${value}` },
    { field: 'tag_amount', name: '吊牌额', formatter: value => `¥${value}` },
    { field: 'discount_rate', name: '折扣率', formatter: value => `${value * 100}%` }
  ],
  fields: {
    rows: [],
    columns: ['big_channel', 'channel', 'month'],
    values: ['gmv', 'tag_amount', 'discount_rate'],
    valueInCols: false
  },
  sortParams: []
})

assert.deepEqual(out.fields.rows, [EXTRA_FIELD])
assert.deepEqual(out.fields.values, ['gmv', 'tag_amount', 'discount_rate'])
const extraMeta = out.meta.find(item => item.field === EXTRA_FIELD)
assert.equal(extraMeta.formatter('gmv'), 'GMV')
assert.equal(extraMeta.formatter('discount_rate'), '折扣率')
assert.equal(out.meta.find(item => item.field === 'gmv').formatter(100), '¥100')
assert.equal(out.meta.find(item => item.field === 'discount_rate').formatter(0.8), '80%')

async function writeSample() {
  const workbook = new ExcelJS.Workbook()
  const sheet = workbook.addWorksheet('透视表')
  const rowLength = 0
  const colLength = 3
  ;['大渠道', '渠道名称'].forEach((name, index) => {
    sheet.getCell(index + 1, 1).value = name
  })
  const head = rowQuotaGridQuotaHeadCell(colLength, rowLength)
  sheet.getCell(head.row, head.col).value = '数值'
  ;['GMV', '吊牌额', '折扣率'].forEach((name, rowIndex) => {
    const pos = rowQuotaGridMeasureCell(rowIndex, 0, colLength)
    sheet.getCell(pos.row, pos.col).value = name
  })
  let colIndex = 0
  ;['京东', '天猫'].forEach(channel => {
    ;['2026-03', '2026-04'].forEach(month => {
      const leaf = rowQuotaGridColLeafCell(2, colIndex, rowLength)
      sheet.getCell(leaf.row, leaf.col).value = month
      const parent = rowQuotaGridColNodeCell(0, colIndex, rowLength)
      if (!sheet.getCell(parent.row, parent.col).value) {
        sheet.getCell(parent.row, parent.col).value = channel
      }
      ;[0, 1, 2].forEach(rowIndex => {
        const data = rowQuotaGridDataCell(rowIndex, colIndex, rowLength, 3)
        sheet.getCell(data.row, data.col).value = (rowIndex + 1) * (colIndex + 1)
      })
      colIndex += 1
    })
  })
  assert.equal(sheet.getCell(4, 1).value, 'GMV')
  assert.equal(sheet.getCell(6, 1).value, '折扣率')
  assert.equal(sheet.getCell(4, 2).value, 1)
  assert.equal(String(sheet.getCell(4, 1).value).includes('$$extra$$'), false)
  const outPath = path.join(os.tmpdir(), 'pivot-measure-rows-sample.xlsx')
  await workbook.xlsx.writeFile(outPath)
  console.log('s2 rows', out.fields.rows.join(','))
  console.log('excel', outPath)
}

writeSample().catch(error => {
  console.error(error)
  process.exit(1)
})
