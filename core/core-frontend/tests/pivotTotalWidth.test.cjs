const fs = require('node:fs')
const path = require('node:path')
const Module = require('node:module')
const ts = require('typescript')
const assert = require('node:assert/strict')
global.window = { devicePixelRatio: 1 }
const {
  PivotDataSet,
  DebuggerUtil,
  DEBUG_HEADER_LAYOUT,
  S2Event,
  EXTRA_FIELD,
  VALUE_FIELD,
  Aggregation
} = require('@antv/s2')
const s2Source = fs.readFileSync(
  path.resolve(__dirname, '../node_modules/@antv/s2/esm/index.js'),
  'utf8'
)
const initSource = s2Source.match(
  /BaseFacet\.prototype\.init = (function \(\) \{[\s\S]*?\n    \});/
)[1]
const initialLayout = new Function(
  'DebuggerUtil',
  'DEBUG_HEADER_LAYOUT',
  'S2Event',
  `return (${initSource})`
)(DebuggerUtil, DEBUG_HEADER_LAYOUT, S2Event)
const file = path.resolve(
  __dirname,
  '../src/views/chart/components/js/panel/charts/table/pivotTotalWidth.ts'
)
const mod = new Module(file)
mod.paths = module.paths
mod._compile(
  ts.transpileModule(fs.readFileSync(file, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText,
  file
)
const { fitPivotTotalColumns } = mod.exports
const format = value =>
  value == null ? '-' : Number(value).toLocaleString('en-US', { maximumFractionDigits: 0 })
const data = [
  { category: 'A', month: '01', sales: 7000000, budget: 1000000000 },
  { category: 'A', month: '02', sales: 6800000, budget: 2000000000 },
  { category: 'B', month: '01', sales: -1234567, budget: 3000000000 }
]
const original = JSON.stringify(data)
const totals = {
  row: { calcTotals: { aggregation: Aggregation.SUM } },
  col: {
    calcTotals: { aggregation: Aggregation.SUM },
    calcSubTotals: { aggregation: Aggregation.SUM }
  }
}
const ds = new PivotDataSet({
  options: { totals },
  store: new Map(),
  isValueInCols: () => true,
  isHierarchyTreeType: () => false
})
ds.setDataCfg({
  fields: {
    rows: ['category'],
    columns: ['month'],
    values: ['sales', 'budget'],
    valueInCols: true
  },
  meta: [
    { field: 'sales', formatter: format },
    { field: 'budget', formatter: format }
  ],
  data,
  totalData: []
})
const fonts = []
const sheet = {
  options: { style: {} },
  dataSet: ds,
  theme: {
    dataCell: {
      cell: { padding: { left: 10, right: 10 } },
      text: { fontSize: 12 },
      bolderText: { fontSize: 14, fontWeight: 'bold' }
    }
  },
  measureTextWidth: (text, font) => {
    fonts.push(font)
    return text.length * font.fontSize
  }
}
const salesTotal = {
  value: 'sales-total',
  width: 60,
  x: 0,
  level: 1,
  query: { [EXTRA_FIELD]: 'sales' },
  isTotals: true,
  isGrandTotals: true
}
const budgetTotal = {
  value: 'budget-total',
  width: 60,
  x: 60,
  level: 1,
  query: { [EXTRA_FIELD]: 'budget' },
  isTotals: true,
  isGrandTotals: true
}
const ordinary = {
  value: '01',
  width: 160,
  x: 120,
  level: 1,
  query: { month: '01', [EXTRA_FIELD]: 'sales' }
}
const parent = { level: 0, x: 0, width: 280, children: [salesTotal, budgetTotal, ordinary] }
const layout = {
  colLeafNodes: parent.children,
  colNodes: [parent, ...parent.children],
  rowLeafNodes: [
    { query: {}, isGrandTotals: true },
    { query: { category: 'A' } },
    { query: { category: 'B' } }
  ],
  colsHierarchy: { width: 280 }
}
// Run S2's real initial layout lifecycle: sheet.facet is not assigned until its
// constructor returns, so LAYOUT_AFTER_HEADER_LAYOUT must work without it.
assert.equal(sheet.facet, undefined)
const stages = []
sheet.emit = (event, result) => {
  if (event === S2Event.LAYOUT_AFTER_HEADER_LAYOUT) {
    stages.push('header')
    fitPivotTotalColumns(sheet, result)
  }
}
const initialFacet = {
  spreadsheet: sheet,
  doLayout: () => layout,
  saveInitColumnLeafNodes: () => {},
  calculateCellWidthHeight: () => stages.push('cells'),
  calculateCornerBBox: () => {},
  calculatePanelBBox: () => {},
  clipPanelGroup: () => {},
  bindEvents: () => {}
}
assert.doesNotThrow(() => initialLayout.call(initialFacet))
assert.deepEqual(stages, ['header', 'cells'], 'initial layout must reach data-cell rendering')
assert.ok(salesTotal.width >= '13,800,000'.length * 14 + 32)
assert.ok(budgetTotal.width >= '6,000,000,000'.length * 14 + 32)
assert.equal(ordinary.width, 160, 'ordinary data column widths must stay unchanged')
assert.equal(budgetTotal.x, salesTotal.width)
assert.equal(ordinary.x, salesTotal.width + budgetTotal.width)
assert.equal(parent.width, layout.colsHierarchy.width)
assert.ok(fonts.every(font => font.fontWeight === 'bold'))
assert.equal(JSON.stringify(data), original)
assert.equal(
  ds.getCellData({ query: { category: 'A', [EXTRA_FIELD]: 'sales' }, isTotals: true })[VALUE_FIELD],
  13800000
)
sheet.options.style.colCfg = { widthByFieldValue: { 'sales-total': 40 } }
salesTotal.width = 40
fitPivotTotalColumns(sheet, layout)
assert.equal(salesTotal.width, 40, 'explicitly resized widths must take precedence')
const subtotal = {
  value: 'subtotal',
  width: 40,
  x: 0,
  level: 0,
  query: { region: 'X', [EXTRA_FIELD]: 'sales' },
  isTotals: true,
  isSubTotals: true
}
ds.setDataCfg({
  fields: {
    rows: ['category'],
    columns: ['region', 'month'],
    values: ['sales', 'budget'],
    valueInCols: true
  },
  meta: [
    { field: 'sales', formatter: format },
    { field: 'budget', formatter: format }
  ],
  data: data.map(row => ({ ...row, region: 'X' })),
  totalData: []
})
fitPivotTotalColumns(sheet, {
  colLeafNodes: [subtotal],
  colNodes: [subtotal],
  rowLeafNodes: [{ query: { category: 'A' } }],
  colsHierarchy: { width: 40 }
})
assert.ok(subtotal.width >= '13,800,000'.length * 14 + 32)
// Hidden single-metric headers have no metric in their query during construction.
sheet.options.style.colCfg = { hideMeasureColumn: true }
ds.setDataCfg({
  fields: { rows: ['category'], columns: ['month'], values: ['sales'], valueInCols: true },
  meta: [{ field: 'sales', formatter: format }],
  data,
  totalData: []
})
const hiddenMetricTotal = {
  width: 40,
  x: 0,
  value: 'total',
  query: {},
  isTotals: true,
  isGrandTotals: true
}
assert.doesNotThrow(() =>
  fitPivotTotalColumns(sheet, {
    colLeafNodes: [hiddenMetricTotal],
    colNodes: [hiddenMetricTotal],
    rowLeafNodes: [{ query: { category: 'A' } }],
    colsHierarchy: { width: 40 }
  })
)
assert.ok(hiddenMetricTotal.width >= '13,800,000'.length * 14 + 32)
console.log(
  'Initial S2 layout without sheet.facet, hidden metrics and total/subtotal widths passed'
)
