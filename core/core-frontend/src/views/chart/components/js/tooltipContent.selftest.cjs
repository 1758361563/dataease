const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const ts = require('typescript')
function load(name) {
  const source = fs.readFileSync(path.join(__dirname, name + '.ts'), 'utf8')
  const output = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText
  const loaded = { exports: {} }
  new Function('module', 'exports', 'require', output)(loaded, loaded.exports, id => load(id))
  return loaded.exports
}
const { editableTooltipFields, explicitTooltipItems, tooltipQueryFields } = load('tooltipContent')
const dimension = { id: 'store', name: '门店' }
const sum = {
  id: 'sales',
  name: '销售额',
  summary: 'sum',
  fieldInstanceId: 'sum',
  formatterCfg: { unit: 1 }
}
const avg = {
  id: 'sales',
  name: '均值',
  summary: 'avg',
  fieldInstanceId: 'avg',
  formatterCfg: { unit: 10 }
}
const chart = {
  xAxis: [dimension],
  yAxis: [sum],
  yAxisExt: [avg],
  customAttr: { tooltip: { show: true, seriesTooltipFormatter: [] } }
}
const snapshot = JSON.stringify(chart)
const old = editableTooltipFields(chart)
assert.equal(old.length, 3)
assert.equal(old[1].tooltipSource, 'axis')
assert.equal(JSON.stringify(chart), snapshot)
const fmt = (value, cfg) => String(Number(value) / (cfg?.unit || 1))
const items = [
  {
    value: 100,
    data: {
      field: 'A',
      category: '销售额',
      dimensionList: [{ id: 'store', value: 'A店' }],
      quotaList: [sum]
    }
  },
  {
    name: 'valueExt',
    value: 20,
    data: {
      field: 'A',
      valueExt: 20,
      category: '均值',
      dimensionList: [{ id: 'store', value: 'A店' }],
      quotaList: [avg]
    }
  }
]
chart.customAttr.tooltip.contentMode = 'explicit'
assert.deepEqual(explicitTooltipItems(chart, items, fmt), [])
chart.customAttr.tooltip.seriesTooltipFormatter = [old[2], old[0], old[1]]
assert.deepEqual(
  explicitTooltipItems(chart, items, fmt).map(item => item.value),
  ['2', 'A店', '100']
)
chart.customAttr.tooltip.seriesTooltipFormatter = [{ ...old[1], show: false }]
assert.deepEqual(explicitTooltipItems(chart, items, fmt), [])
const extra = {
  id: 'sales',
  name: '额外',
  fieldInstanceId: 'extra',
  summary: 'avg',
  tooltipSource: 'extra',
  show: true
}
chart.customAttr.tooltip.seriesTooltipFormatter = [extra]
items[0].data.dynamicTooltipValue = [
  { fieldId: 'sales', fieldInstanceId: 'extra', summary: 'avg', value: 0 }
]
assert.equal(explicitTooltipItems(chart, items, fmt)[0].value, '0')
items[0].data.dynamicTooltipValue[0].value = null
assert.equal(explicitTooltipItems(chart, items, fmt)[0].value, '—')
assert.deepEqual(tooltipQueryFields([old[0], old[1], extra]), [extra])
chart.customAttr.tooltip.seriesTooltipFormatter = [{ ...old[1], tooltipFormatOverride: false }]
sum.formatterCfg.unit = 100
assert.equal(explicitTooltipItems(chart, items, fmt)[0].value, '1')
chart.customAttr.tooltip.seriesTooltipFormatter[0].tooltipFormatOverride = true
chart.customAttr.tooltip.seriesTooltipFormatter[0].formatterCfg = { unit: 10 }
assert.equal(explicitTooltipItems(chart, items, fmt)[0].value, '10')
items[0].data.value = 0.4
items[0].data.__DE_PERCENTAGE_STACK_ORIGIN_VALUE__ = 100
assert.equal(explicitTooltipItems(chart, items, fmt)[0].value, '10')
assert.deepEqual(explicitTooltipItems(chart, [], fmt), [])
const legacy = {
  ...chart,
  customAttr: {
    tooltip: {
      seriesTooltipFormatter: [{ id: 'sales', seriesId: 'sales-yAxis', summary: 'sum', show: true }]
    }
  }
}
assert.equal(editableTooltipFields(legacy)[1].tooltipSource, 'axis')
const roundTrip = JSON.parse(JSON.stringify(chart))
assert.deepEqual(
  explicitTooltipItems(roundTrip, items, fmt),
  explicitTooltipItems(chart, items, fmt)
)
console.log(
  'PASS: legacy preservation, explicit empty, content order, dimensions, dual axes, extra aggregation identity, zero/null, inherited/overridden format, percentage raw values, serialization'
)
const stale = {
  ...chart,
  customAttr: {
    tooltip: { seriesTooltipFormatter: [{ id: 'old', seriesId: 'old-yAxis', show: true }] }
  }
}
assert.equal(editableTooltipFields(stale)[1].tooltipSource, 'unavailable')
assert.deepEqual(tooltipQueryFields(editableTooltipFields(stale)), [])
const removedAxis = JSON.parse(JSON.stringify(chart))
removedAxis.yAxis = []
assert.equal(editableTooltipFields(removedAxis)[0].tooltipUnavailable, true)
assert.deepEqual(explicitTooltipItems(removedAxis, items, fmt), [])
console.log('PASS: stale legacy fields never enter queries; removed axis references do not render')
const commonSource = fs.readFileSync(path.join(__dirname, 'panel/common/common_antv.ts'), 'utf8')
const commonAst = ts.createSourceFile('common.ts', commonSource, ts.ScriptTarget.Latest, true)
const eventFunction = commonAst.statements.find(
  node => ts.isFunctionDeclaration(node) && node.name.text === 'configPlotTooltipEvent'
)
const eventCode = ts.transpileModule(eventFunction.getText(commonAst), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
}).outputText
const eventExports = {}
let cleaned = 0
new Function('exports', 'parseJson', 'ChartCarouselTooltip', eventCode)(
  eventExports,
  value => value,
  { destroyByContainer: () => cleaned++ }
)
for (const tooltip of [false, {}, undefined]) {
  eventExports.configPlotTooltipEvent(
    { customAttr: { tooltip: { show: true } }, container: 'test' },
    { options: { tooltip } }
  )
}
assert.equal(cleaned, 3)
console.log('PASS: disabled or empty tooltip skips DOM binding and cleans carousel state')
const { syncTooltipAxes } = load('tooltipContent')
const automatic = {
  type: 'chart-mix',
  yAxis: [{ ...sum }],
  yAxisExt: [],
  customAttr: { tooltip: { contentMode: 'explicit', autoAxisKeys: [], seriesTooltipFormatter: [] } }
}
syncTooltipAxes(automatic)
assert.equal(automatic.customAttr.tooltip.seriesTooltipFormatter.length, 1)
automatic.customAttr.tooltip.seriesTooltipFormatter = []
syncTooltipAxes(automatic)
assert.equal(automatic.customAttr.tooltip.seriesTooltipFormatter.length, 0)
automatic.yAxisExt = [{ ...avg }]
syncTooltipAxes(automatic)
assert.equal(automatic.customAttr.tooltip.seriesTooltipFormatter.length, 1)
assert.equal(automatic.customAttr.tooltip.seriesTooltipFormatter[0].fieldInstanceId, 'avg')
automatic.yAxisExt[0].chartShowName = '新版均值'
assert.equal(editableTooltipFields(automatic)[0].chartShowName, '新版均值')
automatic.customAttr.tooltip.seriesTooltipFormatter[0].tooltipNameOverride = true
automatic.customAttr.tooltip.seriesTooltipFormatter[0].chartShowName = '单独名称'
assert.equal(editableTooltipFields(automatic)[0].chartShowName, '单独名称')
const persisted = JSON.parse(JSON.stringify(automatic))
syncTooltipAxes(persisted)
assert.equal(persisted.customAttr.tooltip.seriesTooltipFormatter.length, 1)
console.log(
  'PASS: automatic main metrics, explicit clear stays empty, new right metric, inherited/overridden names and persisted exclusions'
)
