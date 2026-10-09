const assert = require('node:assert/strict')
const fs = require('node:fs')
const ts = require('typescript')

const moduleCopy = { exports: {} }
const resultKey = field => (field.fieldInstanceId ? `fi_${field.fieldInstanceId}` : field.dataeaseName)
const activeSortFields = chart => {
  const fields = ['xAxis', 'yAxis', 'xAxisExt']
    .flatMap(axis => (chart[axis] || []).map(field => ({ ...field, axis })))
    .filter(field => ['asc', 'desc', 'custom_sort'].includes(field.sort))
  const ordered = []
  for (const priority of chart.sortPriority || []) {
    for (const field of fields) {
      if (
        String(field.id) === String(priority.id) &&
        (!priority.axis || priority.axis === field.axis) &&
        (!priority.fieldInstanceId || priority.fieldInstanceId === field.fieldInstanceId) &&
        !ordered.includes(field)
      )
        ordered.push(field)
    }
  }
  return [...ordered, ...fields.filter(field => !ordered.includes(field))]
}
const customRequire = id => {
  if (id === 'decimal.js') return require('decimal.js')
  if (id.includes('chartFieldInstance')) return { resultKey }
  if (id.includes('chartDisplayLimit')) return { activeSortFields }
  throw new Error(`Unexpected import: ${id}`)
}
new Function(
  'module',
  'exports',
  'require',
  ts.transpileModule(fs.readFileSync(__dirname + '/pivotGlobalSort.ts', 'utf8'), {
    compilerOptions: {
      module: ts.ModuleKind.CommonJS,
      target: ts.ScriptTarget.ES2020,
      esModuleInterop: true
    }
  }).outputText
)(moduleCopy, moduleCopy.exports, customRequire)

const {
  decodeGlobalPivotValue,
  globalPivotSortReason,
  isGlobalPivotSort,
  pivotSortScope,
  prepareGlobalPivotData
} = moduleCopy.exports

const base = {
  type: 'table-pivot',
  xAxis: [
    { id: 1, dataeaseName: 'category', sort: 'none' },
    { id: 2, dataeaseName: 'sku', sort: 'none' }
  ],
  xAxisExt: [],
  yAxis: [{ id: 3, dataeaseName: 'sales', fieldInstanceId: 'sales_sum', sort: 'desc' }],
  sortPriority: [
    { id: 3, axis: 'yAxis', fieldInstanceId: 'sales_sum', scope: 'GLOBAL' }
  ]
}
const rows = [
  { category: '淡香水', sku: 'A', fi_sales_sum: '100.10', ratio: '0.4' },
  { category: '身体清洁', sku: 'B', fi_sales_sum: '250.20', ratio: '0.5' },
  { category: '淡香水', sku: 'C', fi_sales_sum: '180.30', ratio: '0.1' }
]
assert.equal(pivotSortScope(base), 'GLOBAL')
assert.equal(pivotSortScope(JSON.parse(JSON.stringify(base))), 'GLOBAL')
assert.equal(globalPivotSortReason(base), '')
assert.equal(isGlobalPivotSort(base), true)
const result = prepareGlobalPivotData(base, rows)
assert.equal(result.length, rows.length)
assert.equal(
  new Set(result.map(row => `${decodeGlobalPivotValue(row.category)}/${decodeGlobalPivotValue(row.sku)}`)).size,
  rows.length
)
assert.deepEqual(result.map(row => decodeGlobalPivotValue(row.category)), [
  '身体清洁',
  '淡香水',
  '淡香水'
])
assert.deepEqual(result.map(row => row.fi_sales_sum), ['250.20', '180.30', '100.10'])
assert.deepEqual(result.map(row => row.ratio), ['0.5', '0.1', '0.4'])
assert.deepEqual(rows.map(row => row.category), ['淡香水', '身体清洁', '淡香水'])
assert.notEqual(result[1].category, result[2].category)

const grouped = { ...base, sortPriority: base.sortPriority.map(item => ({ ...item, scope: 'GROUP' })) }
assert.equal(isGlobalPivotSort(grouped), false)
assert.strictEqual(prepareGlobalPivotData(grouped, rows), rows)
assert.ok(globalPivotSortReason({ ...base, xAxisExt: [{ id: 4 }] }))
assert.ok(
  globalPivotSortReason({
    ...base,
    yAxis: base.yAxis.map(field => ({ ...field, compareCalc: { type: 'percent' } }))
  })
)
assert.ok(
  globalPivotSortReason({
    ...base,
    xAxis: base.xAxis.map((field, index) => ({ ...field, sort: index ? 'none' : 'asc' })),
    sortPriority: [{ id: 1, axis: 'xAxis', scope: 'GLOBAL' }]
  })
)
console.log('PASS: pivot global sort uses raw metrics, preserves rows, interleaves groups and keeps GROUP unchanged')
