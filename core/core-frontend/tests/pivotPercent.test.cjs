const fs = require('node:fs'),
  path = require('node:path'),
  Module = require('node:module'),
  assert = require('node:assert/strict'),
  ts = require('typescript')
const { parse, compileScript, compileTemplate } = require('@vue/compiler-sfc')
const file = path.resolve(
  __dirname,
  '../src/views/chart/components/js/panel/charts/table/pivotPercent.ts'
)
const transpile = code =>
  ts.transpileModule(code, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText
const mod = new Module(file)
mod.require = require
mod._compile(transpile(fs.readFileSync(file, 'utf8')), file)
const { pivotPercentValue, isScopedPercent } = mod.exports
const rows = ['channel', 'fi_year', 'category'],
  cols = ['fi_month']
const basis = [
  { channel: 'X', fi_year: '2026', category: 'A', fi_month: '01', sales: '80', count: '8' },
  { channel: 'X', fi_year: '2026', category: 'B', fi_month: '01', sales: '20', count: '2' },
  { channel: 'X', fi_year: '2026', category: 'A', fi_month: '02', sales: '20', count: '2' },
  { channel: 'X', fi_year: '2026', category: 'B', fi_month: '02', sales: '180', count: '18' },
  { channel: 'Y', fi_year: '2026', category: 'A', fi_month: '01', sales: '10', count: '1' },
  { channel: 'Y', fi_year: '2026', category: 'B', fi_month: '01', sales: '30', count: '3' },
  { channel: 'X', fi_year: '2025', category: 'A', fi_month: '01', sales: '5', count: '1' },
  { channel: 'X', fi_year: '2025', category: 'B', fi_month: '01', sales: '5', count: '1' }
]
const original = JSON.stringify(basis)
const field = {
  dataeaseName: 'sales',
  compareCalc: { type: 'percent', percentScope: 'rowGroup', percentGroup: 'fi_year' }
}
const cache = new Map()
const ratio = (q, f = field) => pivotPercentValue(q, basis, rows, cols, f, cache)
const close = (actual, expected) =>
  assert.ok(Math.abs(actual - expected) < 1e-10, `${actual} != ${expected}`)
close(ratio({ ...basis[0] }), 0.8)
close(ratio({ ...basis[2] }), 0.1)
close(ratio({ channel: 'X', fi_year: '2026', category: 'A' }), 1 / 3)
close(ratio({ channel: 'X', fi_year: '2026', category: 'B' }), 2 / 3)
close(ratio({ channel: 'X', fi_year: '2026' }), 1)
close(ratio({ channel: 'Y', fi_year: '2026', category: 'A', fi_month: '01' }), 0.25)
close(ratio({ channel: 'X', fi_year: '2025', category: 'A', fi_month: '01' }), 0.5)
close(
  ratio(
    { channel: 'X', fi_year: '2026', category: 'A', fi_month: '01' },
    { ...field, dataeaseName: 'count' }
  ),
  0.8
)
const row = { ...field, compareCalc: { type: 'percent', percentScope: 'row' } }
close(ratio({ channel: 'X', fi_year: '2026', category: 'B', fi_month: '01' }, row), 0.1)
close(ratio({ channel: 'X', fi_year: '2026', category: 'B' }, row), 1)
const column = { ...field, compareCalc: { type: 'percent', percentScope: 'column' } }
close(ratio({ channel: 'X', fi_year: '2026', category: 'A', fi_month: '01' }, column), 80 / 150)
const size = cache.size
ratio({ ...basis[0] })
assert.equal(cache.size, size)
assert.equal(pivotPercentValue({ channel: 'Z' }, basis, rows, cols, field), null)
assert.equal(pivotPercentValue({}, [{ sales: '0' }], [], [], column), null)
assert.equal(isScopedPercent({ ...field, compareCalc: { type: 'percent' } }), undefined)
assert.throws(
  () =>
    ratio(
      {},
      {
        ...field,
        compareCalc: { type: 'percent', percentScope: 'rowGroup', percentGroup: 'category' }
      }
    ),
  /失效/
)
assert.equal(JSON.stringify(basis), original)
// Exercise the actual project's total method against S2, with backend-style displayed percentages.
global.window = { devicePixelRatio: 1 }
const { PivotDataSet, VALUE_FIELD, EXTRA_FIELD } = require('@antv/s2')
const pivotSource = ts.createSourceFile(
  'pivot.ts',
  fs.readFileSync(
    path.resolve(__dirname, '../src/views/chart/components/js/panel/charts/table/table-pivot.ts'),
    'utf8'
  ),
  ts.ScriptTarget.Latest,
  true
)
let datasetClass
function visit(node) {
  if (ts.isClassDeclaration(node) && node.name?.text === 'CustomPivotDataset')
    datasetClass = node.getText(pivotSource)
  ts.forEachChild(node, visit)
}
visit(pivotSource)
const Dataset = new Function(
  'PivotDataSet',
  'resultKey',
  'isScopedPercent',
  'pivotPercentValue',
  'decodeGlobalPivotValue',
  'VALUE_FIELD',
  'EXTRA_FIELD',
  transpile(datasetClass.replace('class CustomPivotDataset', 'class TestDataset')) +
    '\nreturn TestDataset'
)(
  PivotDataSet,
  f => f.dataeaseName,
  isScopedPercent,
  pivotPercentValue,
  v => v,
  VALUE_FIELD,
  EXTRA_FIELD
)
const dataset = new Dataset({
  options: {},
  store: new Map(),
  isValueInCols: () => true,
  isHierarchyTreeType: () => false
})
const chart = {
  xAxis: rows.map(dataeaseName => ({ dataeaseName })),
  xAxisExt: cols.map(dataeaseName => ({ dataeaseName })),
  yAxis: [field]
}
dataset.percentContext = { chart, basis }
dataset.setDataCfg({
  fields: { rows, columns: cols, values: ['sales'], valueInCols: true },
  data: basis.map(record => ({ ...record, sales: ratio(record) })),
  totalData: [],
  sortParams: []
})
close(
  dataset.getCellData({
    query: { channel: 'X', fi_year: '2026', category: 'A', [EXTRA_FIELD]: 'sales' },
    isTotals: true
  })[VALUE_FIELD],
  1 / 3
)
close(
  dataset.getCellData({
    query: { channel: 'X', fi_year: '2026', [EXTRA_FIELD]: 'sales' },
    isTotals: true
  })[VALUE_FIELD],
  1
)
dataset.setDataCfg({
  fields: { rows, columns: cols, values: ['sales'], valueInCols: true },
  data: [],
  totalData: [],
  sortParams: []
})
assert.equal(dataset.percentTotalsCache.size, 0)
const component = path.resolve(
  __dirname,
  '../src/views/chart/components/editor/drag-item/QuotaItem.vue'
)
const { descriptor, errors } = parse(fs.readFileSync(component, 'utf8'), { filename: component })
assert.deepEqual(errors, [])
const script = compileScript(descriptor, { id: 'percent-test' })
assert.deepEqual(
  compileTemplate({
    source: descriptor.template.content,
    filename: component,
    id: 'percent-test',
    compilerOptions: { bindingMetadata: script.bindings }
  }).errors,
  []
)
console.log(
  'Scoped pivot percentage: month/parent groups, weighted totals, multiple metrics, cache isolation, real S2 total integration, zero denominators and UI compilation passed.'
)
