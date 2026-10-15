const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const ts = require('typescript')
const source = fs.readFileSync(
  path.resolve(__dirname, '../src/views/chart/components/js/panel/charts/table/table-pivot.ts'),
  'utf8'
)
const ast = ts.createSourceFile('table-pivot.ts', source, ts.ScriptTarget.Latest, true)
const table = ast.statements.find(
  node => ts.isClassDeclaration(node) && node.name.text === 'TablePivot'
)
const method = table.members.find(node => node.name?.getText(ast) === 'configSortParams')
const code = ts.transpileModule(
  `class SortProbe { ${method.getText(ast)} }; return new SortProbe()`,
  { compilerOptions: { target: ts.ScriptTarget.ES2020 } }
).outputText
const resultKey = field =>
  field.fieldInstanceId ? `fi_${field.fieldInstanceId}` : field.dataeaseName
const probe = new Function(
  'resultKey',
  'resolveStoredKey',
  'parseJson',
  'keys',
  'TOTAL_VALUE',
  'EXTRA_FIELD',
  code
)(
  resultKey,
  (fields, key) => fields.find(field => field.id === key)?.dataeaseName || key,
  value => (typeof value === 'string' ? JSON.parse(value) : value),
  Object.keys,
  '$$total$$',
  '$$extra$$'
)
const makeChart = () => ({
  xAxis: [{ id: 'channel', dataeaseName: 'channel', sort: 'none', deType: 0 }],
  xAxisExt: [],
  yAxis: [null, undefined, ''].map((sort, index) => ({
    id: `metric${index}`,
    dataeaseName: `metric${index}`,
    sort
  })),
  customAttr: { tableTotal: { row: { totalSort: 'none' }, col: { totalSort: 'none' } } }
})
let chart = makeChart()
const before = JSON.stringify(chart)
assert.deepEqual(probe.configSortParams(chart, []), [])
assert.equal(JSON.stringify(chart), before, 'rendering must not change saved sort settings')
chart.yAxis[1].sort = 'desc'
let sorts = probe.configSortParams(chart, [])
assert.equal(sorts.length, 1)
assert.equal(sorts[0].sortMethod, 'DESC')
assert.equal(sorts[0].query['$$extra$$'], 'metric1')
chart = makeChart()
chart.xAxis[0].sort = null
chart.xAxisExt = [{ id: 'month', dataeaseName: 'month', sort: undefined }]
assert.deepEqual(probe.configSortParams(chart, []), [])
chart.xAxisExt[0].sort = 'asc'
sorts = probe.configSortParams(chart, [])
assert.equal(sorts.length, 1)
assert.equal(sorts[0].sortMethod, 'ASC')
chart = makeChart()
chart.xAxis[0].sort = 'custom_sort'
chart.xAxis[0].customSort = ['B', 'A']
sorts = probe.configSortParams(chart, [])
assert.deepEqual(sorts[0].sortBy, ['B', 'A'])
chart = makeChart()
chart.customAttr.tableTotal.col = {
  showGrandTotals: true,
  totalSort: 'desc',
  totalSortField: 'metric0'
}
sorts = probe.configSortParams(chart, [])
assert.equal(sorts[0].query['$$extra$$'], 'metric0')
assert.equal(sorts[0].sortMethod, 'DESC')
console.log(
  'Pivot nullable sort: three shared metrics, empty/legacy settings, explicit metric/dimension/custom/total sorting and no config mutation passed'
)
