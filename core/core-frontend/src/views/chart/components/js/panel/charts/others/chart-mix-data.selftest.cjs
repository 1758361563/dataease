// Run from core-frontend: node src/views/chart/components/js/panel/charts/others/chart-mix-data.selftest.cjs
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const ts = require('typescript')
const { ScalePool } = require('@antv/g2/lib/chart/util/scale-pool')

function loadTs(name) {
  const source = fs.readFileSync(path.join(__dirname, name), 'utf8')
  const output = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText
  const loaded = { exports: {} }
  new Function('module', 'exports', 'require', output)(loaded, loaded.exports, require)
  return loaded.exports
}
const { alignMixCategories } = loadTs('chart-mix-data.ts')
const { getMixTooltipFormatter } = loadTs('chart-mix-tooltip.ts')
const { clearOtherMixSorts, supportsMixMetricSort } = loadTs('chart-mix-sort.ts')

// G2 Geometry.processData translates categories before View.paint synchronizes scales.
// Exercise the installed scale implementation, including sparse domains.
function renderedLabels(data, values) {
  const pool = new ScalePool()
  const scales = data.map((rows, index) =>
    pool.createScale(
      'field',
      rows,
      {
        type: 'cat',
        sync: true,
        range: [0, 1],
        ...(values ? { values } : {})
      },
      `view-${index}`
    )
  )
  const positions = data.map((rows, index) => rows.map(row => scales[index].translate(row.field)))
  pool.sync({}, {})
  return positions.map(indices => indices.map(index => scales[0].values[index]))
}
const row = (field, value) => ({
  field,
  value,
  category: 'metric',
  quotaList: [{ id: 'same-field' }],
  dimensionList: [{ id: 'channel', value: field }],
  dynamicTooltipValue: [{ value }]
})
const left = [row('A', 900), row('B', 300), row('C', 600)]
const right = [row('C', 10), row('A', 20), row('B', 90)]
assert.notDeepEqual(
  renderedLabels([left, right])[1],
  right.map(row => row.field)
)
console.log('Baseline reproduced: independent category positions mislabel the right series')

const cases = [
  [left, right],
  [[left[2], left[1], left[0]], right],
  [[left[1], left[0], left[2]], right],
  [left, [right[0], row('D', null)]],
  [
    [row('A', 0), row('B', null)],
    [row('B', 0), row('D', 4)]
  ],
  [[...left, { ...left[0], category: 'second-group' }], right],
  [[], right],
  [left, []],
  [[], []],
  // Cancel sort, then deserialize a saved payload and render again.
  [left, right],
  JSON.parse(JSON.stringify([left, right]))
]
for (const input of cases) {
  const before = JSON.stringify(input)
  const aligned = alignMixCategories(...input)
  assert.deepEqual(
    renderedLabels(aligned.data, aligned.values),
    aligned.data.map(rows => rows.map(r => r.field))
  )
  assert.equal(JSON.stringify(input), before)
  aligned.data.forEach((rows, axis) => {
    assert.equal(rows.length, input[axis].length)
    rows.forEach(r => assert.ok(input[axis].includes(r)))
  })
}
assert.deepEqual(alignMixCategories(left, [right[0], row('D', 2)]).values, ['A', 'B', 'C', 'D'])
const formatters = { 'same-field-yAxis': 'bar', 'same-field-yAxisExt': 'line' }
const ranked = alignMixCategories(left, right, ['B', 'C', 'A'])
assert.deepEqual(ranked.values, ['B', 'C', 'A'])
assert.deepEqual(renderedLabels(ranked.data, ranked.values), [
  ['B', 'C', 'A'],
  ['B', 'C', 'A']
])
const sparseRanked = alignMixCategories([row('B', 3)], [row('D', 100), row('B', 90)], ['D', 'B'])
assert.deepEqual(renderedLabels(sparseRanked.data, sparseRanked.values), [['B'], ['D', 'B']])
const sortChart = {
  type: 'chart-mix',
  xAxis: [{ sort: 'asc' }],
  yAxis: [{ id: 'same', fieldInstanceId: 'sum', summary: 'sum', sort: 'none' }],
  yAxisExt: [{ id: 'same', fieldInstanceId: 'avg', summary: 'avg', sort: 'none' }]
}
assert.ok(supportsMixMetricSort(sortChart))
clearOtherMixSorts(sortChart, sortChart.yAxisExt[0], 'desc')
sortChart.yAxisExt[0].sort = 'desc'
assert.equal(sortChart.xAxis[0].sort, 'none')
assert.equal(sortChart.yAxis[0].sort, 'none')
const reloaded = JSON.parse(JSON.stringify(sortChart))
assert.equal(reloaded.yAxisExt[0].sort, 'desc')
clearOtherMixSorts(reloaded, reloaded.yAxis[0], 'asc')
reloaded.yAxis[0].sort = 'asc'
assert.equal(reloaded.yAxisExt[0].sort, 'none')
clearOtherMixSorts(reloaded, reloaded.xAxis[0], 'asc')
assert.equal(reloaded.yAxis[0].sort, 'none')
assert.equal(reloaded.yAxisExt[0].summary, 'avg')
assert.ok(!supportsMixMetricSort({ ...sortChart, extBubble: [{}] }))
console.log(
  'PASS: S2 server category order, sparse axes, placement sort switching and serialization'
)
assert.equal(getMixTooltipFormatter(formatters, { data: left[0] }), 'bar')
assert.equal(getMixTooltipFormatter(formatters, { data: { ...right[0], valueExt: 10 } }), 'line')
console.log(
  `PASS: ${cases.length} alignment scenarios; records, null/zero values and tooltip axis identities preserved`
)
