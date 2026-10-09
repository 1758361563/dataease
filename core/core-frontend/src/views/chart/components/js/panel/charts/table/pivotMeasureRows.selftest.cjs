const assert = require('node:assert/strict')
const fs = require('node:fs')
const ts = require('typescript')

const resultKey = field => (field.fieldInstanceId ? `fi_${field.fieldInstanceId}` : field.dataeaseName)
const customRequire = id => {
  if (id.includes('chartFieldInstance')) return { resultKey }
  if (id === '../../../util' || id.endsWith('/util')) {
    return { parseJson: value => (typeof value === 'string' ? JSON.parse(value) : value) }
  }
  throw new Error(`Unexpected import: ${id}`)
}
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
)(moduleCopy, moduleCopy.exports, customRequire)

const {
  pivotAllowsEmptyRowDimension,
  isBlockedMeasureOnlyTree,
  buildPivotS2Fields,
  s2RowsAfterProcess,
  pivotExportAllowsEmptyRows,
  readGridColTotal,
  rowQuotaGridMeasureCell,
  rowQuotaGridDataCell,
  rowQuotaGridQuotaHeadCell,
  rowQuotaGridColLeafCell,
  rowQuotaGridColNodeCell
} = moduleCopy.exports

const dim = name => ({ dataeaseName: name, id: name })
const quota = name => ({ dataeaseName: name, id: name, formatterCfg: { type: name } })

function chart(partial) {
  return {
    type: 'table-pivot',
    customAttr: {
      basicStyle: {
        quotaPosition: 'col',
        tableLayoutMode: 'grid',
        ...partial.basicStyle
      }
    },
    xAxis: partial.xAxis || [],
    xAxisExt: partial.xAxisExt || [],
    yAxis: partial.yAxis || []
  }
}

const measures = [quota('gmv'), quota('tag_amount'), quota('discount_rate')]
const columns = [dim('big_channel'), dim('channel'), dim('month')]

function fieldsOf(partial) {
  return buildPivotS2Fields(chart(partial))
}

// Case 1
const case1 = fieldsOf({ xAxis: [dim('month')], yAxis: [quota('gmv')] })
assert.deepEqual(case1.rows, ['month'])
assert.deepEqual(case1.values, ['gmv'])
assert.equal(case1.valueInCols, true)
assert.equal(pivotAllowsEmptyRowDimension(chart({ xAxis: [dim('month')], yAxis: [quota('gmv')] })), false)

// Case 2 / 3
const case3 = fieldsOf({
  xAxis: [dim('month'), dim('brand')],
  xAxisExt: columns,
  yAxis: measures
})
assert.deepEqual(case3.rows, ['month', 'brand'])
assert.deepEqual(case3.columns, ['big_channel', 'channel', 'month'])
assert.deepEqual(case3.values, ['gmv', 'tag_amount', 'discount_rate'])

// Case 4 指标列头 + 有行维度
const case4 = chart({
  basicStyle: { quotaPosition: 'col' },
  xAxis: [dim('month')],
  xAxisExt: columns,
  yAxis: measures
})
assert.equal(pivotAllowsEmptyRowDimension(case4), false)
assert.equal(case4.customAttr.basicStyle.quotaPosition === 'row', false)
const case4Fields = buildPivotS2Fields(case4)
assert.equal(case4Fields.valueInCols, true)
assert.deepEqual(s2RowsAfterProcess(case4Fields), ['month'])

// Case 5 指标行头 + 真实行维度
const case5 = chart({
  basicStyle: { quotaPosition: 'row', tableLayoutMode: 'grid' },
  xAxis: [dim('brand_code')],
  xAxisExt: columns,
  yAxis: measures
})
assert.equal(pivotAllowsEmptyRowDimension(case5), true)
const case5Fields = buildPivotS2Fields(case5)
assert.equal(case5Fields.valueInCols, false)
assert.deepEqual(s2RowsAfterProcess(case5Fields), ['brand_code', '$$extra$$'])

// Case 6
const case6 = chart({
  basicStyle: { quotaPosition: 'row', tableLayoutMode: 'grid' },
  xAxis: [],
  xAxisExt: columns,
  yAxis: measures
})
assert.equal(pivotAllowsEmptyRowDimension(case6), true)
assert.equal(isBlockedMeasureOnlyTree(case6), false)
const case6Fields = buildPivotS2Fields(case6)
assert.deepEqual(case6Fields.rows, [])
assert.deepEqual(case6Fields.columns, ['big_channel', 'channel', 'month'])
assert.deepEqual(case6Fields.values, ['gmv', 'tag_amount', 'discount_rate'])
assert.equal(case6Fields.valueInCols, false)
assert.deepEqual(s2RowsAfterProcess(case6Fields), ['$$extra$$'])
assert.equal(pivotExportAllowsEmptyRows(case6, case6Fields.values.length), true)
assert.equal(pivotExportAllowsEmptyRows(case6, 0), false)

// Case 7 列头 + 空行维度仍非法
const case7 = chart({
  basicStyle: { quotaPosition: 'col', tableLayoutMode: 'grid' },
  xAxis: [],
  yAxis: measures
})
assert.equal(pivotAllowsEmptyRowDimension(case7), false)
assert.equal(pivotExportAllowsEmptyRows(case7, 3), false)

// Case 8 树形 + 行头 + 空行维度仍非法
const case8 = chart({
  basicStyle: { quotaPosition: 'row', tableLayoutMode: 'tree' },
  xAxis: [],
  yAxis: measures
})
assert.equal(pivotAllowsEmptyRowDimension(case8), false)
assert.equal(isBlockedMeasureOnlyTree(case8), true)
assert.equal(pivotExportAllowsEmptyRows(case8, 3), false)

// 其他图表不放开
assert.equal(
  pivotAllowsEmptyRowDimension({
    type: 'table-normal',
    customAttr: { basicStyle: { quotaPosition: 'row' } },
    xAxis: []
  }),
  false
)

// 指标顺序保持 yAxis，不按名称排序
assert.deepEqual(case6Fields.values, ['gmv', 'tag_amount', 'discount_rate'])
assert.notDeepEqual(case6Fields.values, ['discount_rate', 'gmv', 'tag_amount'])

// 每个指标仍带着自己的 formatter
assert.equal(case6.yAxis[0].formatterCfg.type, 'gmv')
assert.equal(case6.yAxis[2].formatterCfg.type, 'discount_rate')

// 0 行维度的列总计读指标根，有行维度仍走路径
assert.equal(readGridColTotal([], { gmv: 12, discount_rate: 0.3 }, 'discount_rate', []), 0.3)
assert.equal(
  readGridColTotal([{ id: 'brand' }], { A: { gmv: 9 } }, 'gmv', ['A']),
  9
)
assert.equal(readGridColTotal([{ id: 'brand' }], { gmv: 9 }, 'gmv', []), undefined)

// Excel：0 行维度时第 1 列是指标名，数据从第 2 列开始；有行维度时列号后移
assert.deepEqual(rowQuotaGridMeasureCell(0, 0, 3), { row: 4, col: 1 })
assert.deepEqual(rowQuotaGridDataCell(0, 0, 0, 3), { row: 4, col: 2 })
assert.deepEqual(rowQuotaGridDataCell(0, 1, 0, 3), { row: 4, col: 3 })
assert.deepEqual(rowQuotaGridQuotaHeadCell(3, 0), { row: 3, col: 1 })
assert.deepEqual(rowQuotaGridColLeafCell(2, 0, 0), { row: 3, col: 2 })
assert.deepEqual(rowQuotaGridColNodeCell(0, 0, 0), { row: 1, col: 1 })
assert.deepEqual(rowQuotaGridDataCell(0, 0, 1, 3), { row: 4, col: 3 })
assert.deepEqual(rowQuotaGridMeasureCell(0, 1, 3), { row: 4, col: 2 })
assert.equal(rowQuotaGridQuotaHeadCell(3, 1).col, 2)

console.log('pivotMeasureRows.selftest: PASS')
