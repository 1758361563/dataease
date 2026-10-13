const fs = require('node:fs')
const path = require('node:path')
const Module = require('node:module')
const assert = require('node:assert/strict')
const ts = require('typescript')
const { parse, compileScript, compileTemplate } = require('@vue/compiler-sfc')
// S2 accesses DPR at import time; dataset tests do not create a DOM or canvas.
global.window = { devicePixelRatio: 1 }
const { PivotDataSet, EXTRA_FIELD, VALUE_FIELD, Aggregation, QueryDataType } = require('@antv/s2')
const file = path.resolve(
  __dirname,
  '../src/views/chart/components/js/panel/charts/table/pivotHeaderLayout.ts'
)
const mod = new Module(file)
const transpile = code =>
  ts.transpileModule(code, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText
mod._compile(transpile(fs.readFileSync(file, 'utf8')), file)
const { pivotHeaderFields } = mod.exports
const source = ts.createSourceFile(
  'pivot.ts',
  fs.readFileSync(
    path.resolve(__dirname, '../src/views/chart/components/js/panel/charts/table/table-pivot.ts'),
    'utf8'
  ),
  ts.ScriptTarget.Latest,
  true
)
let datasetClass, totalResolver, totalAlias
function visit(node) {
  if (ts.isClassDeclaration(node) && node.name?.text === 'CustomPivotDataset')
    datasetClass = node.getText(source)
  if (ts.isFunctionDeclaration(node) && node.name?.text === 'getAggregationAndCalcFuncByQuery')
    totalResolver = node.getText(source)
  if (
    ts.isBinaryExpression(node) &&
    node.left.getText(source) === 's2Options.layoutCoordinate' &&
    ts.isArrowFunction(node.right) &&
    node.right.parameters[2]?.name.getText(source) === 'col'
  )
    totalAlias = node.right.getText(source)
  ts.forEachChild(node, visit)
}
visit(source)
const CustomDataset = new Function(
  'PivotDataSet',
  'isEmpty',
  'Aggregation',
  'QueryDataType',
  'calcActionByType',
  'some',
  'EXTRA_FIELD',
  'VALUE_FIELD',
  transpile(
    totalResolver + '\n' + datasetClass.replace('class CustomPivotDataset', 'class TestDataset')
  ) + '\nreturn TestDataset'
)(
  PivotDataSet,
  value => !value || Object.keys(value).length === 0,
  Aggregation,
  QueryDataType,
  {
    [Aggregation.SUM]: (items, field) =>
      items.reduce((sum, item) => sum + Number(item[field] || 0), 0)
  },
  value => Object.values(value || {}).some(Boolean),
  EXTRA_FIELD,
  VALUE_FIELD
)
const rows = [
  { year: '2026', category: 'A', month: '08', channel: '门店', sales: 20, budget: 30 },
  { year: '2026', category: 'A', month: '07', channel: '门店', sales: 10, budget: 15 },
  { year: '2026', category: 'B', month: '07', channel: '线上', sales: 5, budget: 8 }
]
const original = JSON.stringify(rows)
for (const columns of [['month'], ['channel', 'month']]) {
  for (const values of [['sales'], ['sales', 'budget']]) {
    const results = []
    for (const order of ['dimension-first', 'measure-first']) {
      const style = { quotaPosition: 'col', pivotHeaderOrder: order }
      const fields = pivotHeaderFields(['year', 'category'], columns, values, style)
      // The setting survives the existing JSON persistence format.
      assert.deepEqual(
        pivotHeaderFields(['year', 'category'], columns, values, JSON.parse(JSON.stringify(style))),
        fields
      )
      const totals = {
        row: { calcTotals: { aggregation: Aggregation.SUM } },
        col: { calcTotals: { aggregation: Aggregation.SUM } }
      }
      const sheet = {
        options: { totals },
        store: new Map(),
        isValueInCols: () => true,
        isHierarchyTreeType: () => false
      }
      const ds = new CustomDataset(sheet)
      ds.setDataCfg({
        fields,
        data: rows,
        totalData: [],
        sortParams: [{ sortFieldId: 'month', sortMethod: 'ASC' }]
      })
      assert.deepEqual(
        ds.fields.columns,
        order === 'measure-first' ? [EXTRA_FIELD, ...columns] : [...columns, EXTRA_FIELD]
      )
      if (columns.includes('month')) assert.deepEqual(ds.getDimensionValues('month'), ['07', '08'])
      const result = values.map(measure => ({
        details: rows.map(row => {
          const query = { year: row.year, category: row.category, [EXTRA_FIELD]: measure }
          for (const column of columns) query[column] = row[column]
          return ds.getCellData({ query })?.[VALUE_FIELD]
        }),
        grand: ds.getCellData({ query: { [EXTRA_FIELD]: measure }, isTotals: true })?.[VALUE_FIELD],
        subtotal: ds.getCellData({
          query: { year: '2026', category: 'A', [EXTRA_FIELD]: measure },
          isTotals: true
        })?.[VALUE_FIELD]
      }))
      results.push(result)
      assert.equal(result[0].grand, 35)
      assert.equal(result[0].subtotal, 30)
      if (values.length === 2) assert.equal(result[1].grand, 53)
    }
    assert.deepEqual(
      results[0],
      results[1],
      'header order must preserve cells, totals and subtotals'
    )
  }
}
assert.equal(JSON.stringify(rows), original)
assert.equal(pivotHeaderFields([], ['month'], ['sales'], {}).customValueOrder, undefined)
assert.equal(
  pivotHeaderFields([], ['month'], ['sales'], {
    quotaPosition: 'row',
    pivotHeaderOrder: 'measure-first'
  }).customValueOrder,
  undefined
)
const alias = new Function(
  'EXTRA_FIELD',
  'colTotalCfgMap',
  transpile(`const apply=${totalAlias}`) + '\nreturn apply'
)(EXTRA_FIELD, { sales: { label: '业绩合计' } })
for (const node of [
  { isGrandTotals: true, value: 'sales' },
  { isGrandTotals: true, value: '总计', query: { [EXTRA_FIELD]: 'sales' } }
]) {
  alias(null, null, node)
  assert.equal(node.label, '业绩合计')
}
const component = path.resolve(
  __dirname,
  '../src/views/chart/components/editor/editor-style/components/BasicStyleSelector.vue'
)
const { descriptor, errors } = parse(fs.readFileSync(component, 'utf8'), { filename: component })
assert.deepEqual(errors, [])
const script = compileScript(descriptor, { id: 'pivot-header-test' })
assert.deepEqual(
  compileTemplate({
    source: descriptor.template.content,
    filename: component,
    id: 'pivot-header-test',
    compilerOptions: { bindingMetadata: script.bindings }
  }).errors,
  []
)
console.log(
  'Pivot header order passed: real S2 dataset, single/multiple measures and dimensions, month sorting, project totals/subtotals/aliases, JSON persistence and editor compilation.'
)
