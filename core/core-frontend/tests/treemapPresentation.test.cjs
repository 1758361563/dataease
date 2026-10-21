const fs = require('node:fs')
const path = require('node:path')
const Module = require('node:module')
const assert = require('node:assert/strict')
const ts = require('typescript')
const root = path.resolve(__dirname, '../src/views/chart/components/js')
const cache = new Map()
const transpile = source =>
  ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText
function load(file) {
  file = path.resolve(file)
  if (cache.has(file)) return cache.get(file).exports
  const mod = new Module(file, module)
  cache.set(file, mod)
  mod.require = name =>
    name.startsWith('.') ? load(path.resolve(path.dirname(file), name + '.ts')) : require(name)
  mod._compile(transpile(fs.readFileSync(file, 'utf8')), file)
  return mod.exports
}
const { treemapTooltip, treemapTitle, treemapDatum, treemapPercent, highlightTreemap } = load(
  path.join(root, 'panel/charts/others/treemapPresentation.ts')
)
const { supportsTooltipShelf, editableTooltipFields } = load(path.join(root, 'tooltipContent.ts'))
assert.equal(supportsTooltipShelf('treemap'), true)
const amount = {
  id: 'sales',
  name: '业绩',
  summary: 'sum',
  deType: 2,
  groupType: 'q',
  formatterCfg: { decimalCount: 0, thousandSeparator: true }
}
const chart = {
  type: 'treemap',
  xAxis: [{ id: 'category', name: '品类' }],
  yAxis: [amount],
  extTooltip: [{ id: 'quantity', name: '数量' }],
  customAttr: {
    label: { reserveDecimalCount: 4 },
    tooltip: { show: true, fontSize: 12, seriesTooltipFormatter: [] }
  }
}
const format = (value, cfg) =>
  Number(value).toLocaleString('en-US', {
    minimumFractionDigits: cfg?.decimalCount || 0,
    maximumFractionDigits: cfg?.decimalCount || 0,
    useGrouping: cfg?.thousandSeparator !== false
  }) + (cfg?.suffix || '')
const raw = {
  field: '香氛身体乳',
  name: '香氛身体乳',
  value: 12345.678,
  quotaList: [{ id: 'sales', summary: 'sum' }],
  dimensionList: [{ id: 'category', value: '香氛身体乳' }],
  dynamicTooltipValue: [{ fieldId: 'quantity', value: 20.5 }]
}
// Exercise G2's hierarchy representation, with metadata inside node.data.
const datum = {
  data: raw,
  value: raw.value,
  path: [{ data: raw, value: raw.value }, { value: 50000 }]
}
const items = [{ name: 'value', value: raw.value, data: datum, color: '#4E76CC' }]
let tooltip = treemapTooltip(chart, 99999, format)
assert.equal(tooltip.title('internal-node-key', datum), '香氛身体乳')
assert.deepEqual(
  tooltip.customItems(items).map(row => [row.name, row.value]),
  [
    ['业绩', '12,346'],
    ['占比', '24.6914%']
  ]
)
assert.equal(tooltip.domStyles['g2-tooltip'].fontSize, '14px')
assert.equal(tooltip.domStyles['g2-tooltip'].width, 'max-content')
assert.equal(tooltip.domStyles['g2-tooltip-list-item'].justifyContent, 'flex-start')
assert.deepEqual(tooltip.customItems([]), [])
assert.equal(treemapTitle({ field: null }), '未分类')
assert.equal(treemapTitle({ field: '' }), '（空字符串）')
assert.equal(treemapPercent(0, 0), '—')
assert.equal(treemapPercent(null, 5), '—')
assert.equal(treemapPercent(1, 4, 1), '25.0%')

// Legacy formats remain in force until edited. Editing a measure uses the same
// explicit field shelf, and its decimal precision is independent of labels.
const explicit = structuredClone(chart)
explicit.customAttr.tooltip.seriesTooltipFormatter = [
  {
    ...amount,
    chartShowName: '销售额',
    show: true,
    formatterCfg: { decimalCount: 1, thousandSeparator: false }
  }
]
assert.equal(treemapTooltip(explicit, 50000, format).customItems(items)[0].value, '12345.7')
const fields = editableTooltipFields(explicit)
const main = fields.find(field => field.tooltipSource === 'axis')
main.formatterCfg = { decimalCount: 2, thousandSeparator: true, suffix: '元' }
main.tooltipFormatOverride = true
fields.push({
  id: 'quantity',
  name: '件数',
  tooltipSource: 'extra',
  show: true,
  formatterCfg: { decimalCount: 1 }
})
explicit.customAttr.tooltip = {
  ...explicit.customAttr.tooltip,
  contentMode: 'explicit',
  reserveDecimalCount: 1,
  seriesTooltipFormatter: fields
}
tooltip = treemapTooltip(explicit, 50000, format)
assert.equal(tooltip.showTitle, false)
assert.deepEqual(
  tooltip.customItems(items).map(row => [row.name, row.value]),
  [
    ['品类', '香氛身体乳'],
    ['销售额', '12,345.68元'],
    ['件数', '20.5'],
    ['占比', '24.7%']
  ]
)
explicit.customAttr.label.reserveDecimalCount = 8
assert.equal(treemapTooltip(explicit, 50000, format).customItems(items).at(-1).value, '24.7%')
explicit.customAttr.tooltip.showProportion = false
assert.equal(treemapTooltip(explicit, 50000, format).customItems(items).length, 3)
main.show = false
explicit.customAttr.tooltip.showProportion = true
assert.ok(
  !treemapTooltip(explicit, 50000, format)
    .customItems(items)
    .some(row => row.name === '销售额' || row.name === '占比')
)
explicit.customAttr.tooltip.seriesTooltipFormatter = []
assert.equal(treemapTooltip(explicit, 50000, format), false)
assert.equal(
  treemapTooltip(
    { ...chart, customAttr: { ...chart.customAttr, tooltip: { show: false } } },
    1,
    format
  ),
  false
)
assert.equal(raw.value, 12345.678)

// Execute the real renderer with a plot spy: query data must survive sorting,
// click linkage must retain the query metadata, and hover/reset must be wired.
const rendererFile = path.join(root, 'panel/charts/others/treemap.ts')
const ast = ts.createSourceFile(
  'treemap.ts',
  fs.readFileSync(rendererFile, 'utf8'),
  ts.ScriptTarget.Latest,
  true
)
const declaration = ast.statements.find(ts.isClassDeclaration)
const methods = declaration.members
  .filter(n => ts.isMethodDeclaration(n))
  .map(n => n.getText(ast))
  .join('\n')
class Plot {
  constructor(container, options) {
    this.options = options
    this.handlers = {}
    this.states = {}
  }
  on(event, callback) {
    this.handlers[event] = callback
  }
  setState(state, predicate, enabled) {
    for (const datum of this.options.data.children) {
      if (!predicate(datum)) continue
      this.states[datum.treemapKey] ||= {}
      this.states[datum.treemapKey][state] = enabled
    }
  }
}
const View = new Function(
  'Base',
  'require',
  'cloneDeep',
  'flow',
  'getPadding',
  'parseJson',
  'treemapTooltip',
  'treemapPercent',
  'treemapDatum',
  'highlightTreemap',
  'valueFormatter',
  'setUpSingleDimensionSeriesColor',
  transpile('return class extends Base {' + methods + '}')
)(
  class {
    configTheme(_chart, options) {
      return options
    }
    configLegend(_chart, options) {
      return options
    }
    configSingleDimensionColor(_chart, options) {
      return { ...options, color: ['blue', 'yellow'] }
    }
  },
  name => {
    assert.equal(name, '@antv/g2plot/esm/plots/treemap')
    return { Treemap: Plot }
  },
  structuredClone,
  (...steps) =>
    (chart, options, _context, instance) =>
      steps.reduce((result, step) => step.call(instance, chart, result), options),
  () => 0,
  value => (typeof value === 'string' ? JSON.parse(value) : value),
  treemapTooltip,
  treemapPercent,
  treemapDatum,
  highlightTreemap,
  format,
  (_chart, rows) => rows.map(row => row.field)
)
async function run() {
  const renderChart = structuredClone(chart)
  renderChart.customAttr.label = {
    show: true,
    showDimension: true,
    showQuota: true,
    showProportion: true,
    reserveDecimalCount: 2
  }
  renderChart.customAttr = JSON.stringify(renderChart.customAttr)
  renderChart.data = {
    data: [
      { ...raw, value: 1 },
      { ...raw, field: '礼盒', name: '礼盒', value: 5 }
    ]
  }
  const before = JSON.stringify(renderChart)
  const view = new View()
  assert.deepEqual(view.setupSeriesColor(chart, renderChart.data.data), ['礼盒', '香氛身体乳'])
  const click = () => {}
  const plot = await view.drawChart({ chart: renderChart, container: 'plot', action: click })
  assert.equal(JSON.stringify(renderChart), before)
  assert.equal(plot.handlers['polygon:click'], click)
  assert.deepEqual(
    plot.options.data.children.map(row => row.field),
    ['礼盒', '香氛身体乳']
  )
  assert.ok(plot.options.rawFields.includes('dimensionList'))
  assert.equal(
    plot.options.label
      .formatter({ ...raw, parent: { value: 0 } })
      .split('\n')
      .at(-1),
    '—'
  )
  const selected = plot.options.data.children[0]
  plot.handlers['element:mouseenter']({ data: { data: selected } })
  assert.equal(plot.states[selected.treemapKey].active, true)
  assert.equal(plot.states[plot.options.data.children[1].treemapKey].inactive, true)
  plot.handlers['plot:leave']()
  assert.ok(Object.values(plot.states).every(states => !states.active && !states.inactive))
  console.log(
    'Treemap: legacy/explicit formats, dimensions, extra fields, percentages, compact tooltip, immutable rendering and hover/reset passed'
  )
}
run().catch(error => {
  console.error(error)
  process.exitCode = 1
})
