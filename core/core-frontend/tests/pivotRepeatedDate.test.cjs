const fs = require('node:fs')
const path = require('node:path')
const Module = require('node:module')
const vm = require('node:vm')
const ts = require('typescript')
const assert = require('node:assert/strict')
const { parse, compileScript, compileTemplate } = require('@vue/compiler-sfc')
const transpile = code =>
  ts.transpileModule(code, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText
const file = path.resolve(__dirname, '../src/views/chart/components/js/chartFieldInstance.ts')
const mod = new Module(file)
mod._compile(transpile(fs.readFileSync(file, 'utf8')), file)
const {
  allowsRepeatedPivotDate,
  instanceKey,
  ensurePivotDimensionInstances,
  resultKey,
  formatPivotDate
} = mod.exports
const editor = fs.readFileSync(
  path.resolve(__dirname, '../src/views/chart/components/editor/index.vue'),
  'utf8'
)
const { descriptor } = parse(editor)
const ast = ts.createSourceFile(
  'editor.ts',
  descriptor.scriptSetup.content,
  ts.ScriptTarget.Latest,
  true
)
const functions = new Map()
for (const statement of ast.statements) {
  if (ts.isVariableStatement(statement))
    for (const declaration of statement.declarationList.declarations) {
      if (ts.isIdentifier(declaration.name))
        functions.set(declaration.name.text, `const ${declaration.getText(ast)}`)
    }
}
const chart = { type: 'table-pivot', xAxis: [], xAxisExt: [], yAxis: [] }
let sequence = 0
const context = {
  view: { value: chart },
  state: { quotaData: [] },
  activeDimension: { value: [] },
  activeQuota: { value: [] },
  recordSnapshotInfo: () => {},
  applyPlacementRoleOnDrop: () => true,
  chartViewInstance: {
    value: { axisConfig: { xAxis: {}, xAxisExt: {}, yAxis: { duplicate: true } } }
  },
  emitter: { emit: () => {} },
  guid: () => `new-${++sequence}`,
  cloneDeep: value => JSON.parse(JSON.stringify(value)),
  merge: (...values) => Object.assign({}, ...values),
  dvMainStore: { canvasStyleData: { component: { formatterItem: {} } } },
  allowsRepeatedPivotDate,
  instanceKey,
  exports: {}
}
vm.createContext(context)
vm.runInContext(
  transpile(
    ['dragMoveDuplicate', 'addAxis', 'drop', 'dimensionItemKey']
      .map(name => functions.get(name))
      .join('\n') + '\nexports.drop = drop; exports.key = dimensionItemKey'
  ),
  context
)
const field = { id: 'date', dataeaseName: 'date', deType: 1, dateStyle: 'y_M_d' }
const event = { preventDefault() {}, srcElement: { offsetParent: { querySelector: () => null } } }
for (let i = 0; i < 2; i++) {
  context.activeDimension.value = [field]
  context.exports.drop(event, 'xAxisExt')
}
assert.equal(chart.xAxisExt.length, 2, 'actual editor drop must retain both date placements')
assert.notEqual(context.exports.key(chart.xAxisExt[0]), context.exports.key(chart.xAxisExt[1]))
chart.xAxisExt[1].dateShowFormat = 'weekday_name'
assert.equal(chart.xAxisExt[0].dateShowFormat, undefined, 'date formats must be independent')
const persisted = JSON.parse(JSON.stringify(chart))
ensurePivotDimensionInstances(persisted)
assert.notEqual(resultKey(persisted.xAxisExt[0]), resultKey(persisted.xAxisExt[1]))
assert.equal(formatPivotDate('2026-10-13', persisted.xAxisExt[0]), '2026-10-13')
assert.equal(formatPivotDate('2026-10-13', persisted.xAxisExt[1]), '星期二')
const weekly = persisted.xAxisExt.map((f, index) => ({
  ...f,
  dateStyle: 'y_W',
  dateShowFormat: index ? 'week_range' : 'week_year'
}))
assert.equal(formatPivotDate('2025-12-29', weekly[0]), '2026年第1周')
assert.equal(formatPivotDate('2025-12-29', weekly[1]), '[2025-12-29, 2026-01-04]')
assert.equal(allowsRepeatedPivotDate({ type: 'bar' }, 'xAxis', field), false)
assert.equal(allowsRepeatedPivotDate(chart, 'xAxisExt', { deType: 0 }), false)
context.activeDimension.value = [{ id: 'category', deType: 0 }]
context.exports.drop(event, 'xAxis')
context.exports.drop(event, 'xAxis')
assert.equal(chart.xAxis.length, 1, 'non-date deduplication must remain')
const script = compileScript(descriptor, { id: 'repeated-date-editor' })
assert.deepEqual(
  compileTemplate({
    source: descriptor.template.content,
    filename: 'index.vue',
    id: 'repeated-date-editor',
    compilerOptions: { bindingMetadata: script.bindings }
  }).errors,
  []
)
global.window = { devicePixelRatio: 1 }
const { PivotDataSet, EXTRA_FIELD, VALUE_FIELD, Aggregation } = require('@antv/s2')
for (const dimensions of [persisted.xAxisExt, weekly]) {
  const keys = dimensions.map(resultKey)
  const date = dimensions[0].dateStyle === 'y_W' ? '2025-12-29' : '2026-10-13'
  const ds = new PivotDataSet({
    options: {
      totals: {
        row: { calcTotals: { aggregation: Aggregation.SUM } },
        col: { calcTotals: { aggregation: Aggregation.SUM } }
      }
    },
    store: new Map(),
    isValueInCols: () => true,
    isHierarchyTreeType: () => false
  })
  ds.setDataCfg({
    fields: { rows: ['category'], columns: keys, values: ['sales'], valueInCols: true },
    data: [{ category: 'A', [keys[0]]: date, [keys[1]]: date, sales: 100 }],
    totalData: []
  })
  assert.equal(
    ds.getCellData({
      query: { category: 'A', [keys[0]]: date, [keys[1]]: date, [EXTRA_FIELD]: 'sales' }
    })[VALUE_FIELD],
    100
  )
}
console.log('Repeated date drop, independent formats, persistence and real S2 cells passed')
