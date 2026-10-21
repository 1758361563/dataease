const fs = require('node:fs')
const path = require('node:path')
const Module = require('node:module')
const assert = require('node:assert/strict')
const ts = require('typescript')
const base = path.resolve(__dirname, '../src/views/chart/components/js/panel/charts/line')
const file = path.join(base, 'lineOrder.ts')
const mod = new Module(file, module)
const transpile = source =>
  ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText
mod._compile(transpile(fs.readFileSync(file, 'utf8')), file)
const { orderLineData } = mod.exports
const dateChart = { xAxis: [{ deType: 1, sort: 'none' }], drill: [] }
const point = (category, field, value) => ({ category, field, value, quotaList: [{ id: 'sales' }] })
// Simulate SQL ordering by sales: categories and dates are interleaved, and a
// sparse series discovers an earlier date after the axis has already begun.
const input = {
  data: [
    point('香水', '2026-09-09', 900),
    point('身体乳', '2026-08-07', 700),
    point('香水', '2026-09-07', 600),
    point('身体乳', '2026-09-09', 400),
    point('香水', '2026-08-07', 300),
    point('洗发水', '2026-08-01', null)
  ],
  xField: 'field',
  yField: 'value',
  seriesField: 'category',
  meta: { category: { type: 'cat', values: ['身体乳', '香水', '洗发水'] } },
  connectNulls: false
}
const snapshot = JSON.stringify(input)
const ordered = orderLineData(dateChart, input)
assert.deepEqual(ordered.meta.field.values, [
  '2026-08-01',
  '2026-08-07',
  '2026-09-07',
  '2026-09-09'
])
assert.deepEqual(
  ordered.data.filter(p => p.category === '香水').map(p => p.field),
  ['2026-08-07', '2026-09-07', '2026-09-09']
)
assert.deepEqual([...new Set(ordered.data.map(p => p.category))], ['香水', '身体乳', '洗发水'])
assert.equal(JSON.stringify(input), snapshot)
assert.equal(ordered.meta.category, input.meta.category)
assert.equal(ordered.connectNulls, false)
input.data.forEach(p => assert.ok(ordered.data.includes(p))) // values/metadata unchanged
const descending = orderLineData({ xAxis: [{ deType: 1, sort: 'desc' }] }, input)
assert.deepEqual(descending.meta.field.values, [...ordered.meta.field.values].reverse())
const custom = orderLineData(
  { xAxis: [{ deType: 1, sort: 'custom_sort', customSort: ['2026-09-07', '2026-08-07'] }] },
  input
)
assert.deepEqual(custom.meta.field.values, ['2026-09-07', '2026-08-07', '2026-08-01', '2026-09-09'])
// Categorical charts keep their existing domain, but all series follow it.
const categorical = orderLineData({ xAxis: [{ deType: 0 }] }, input)
assert.deepEqual(categorical.meta.field.values, [
  '2026-09-09',
  '2026-08-07',
  '2026-09-07',
  '2026-08-01'
])
for (const result of [ordered, descending, custom, categorical]) {
  for (const category of new Set(result.data.map(p => p.category))) {
    const ranks = result.data
      .filter(p => p.category === category)
      .map(p => result.meta.field.values.indexOf(p.field))
    assert.deepEqual(
      ranks,
      [...ranks].sort((a, b) => a - b)
    )
  }
}
const months = orderLineData(dateChart, {
  ...input,
  data: ['2026/10', '2026/2', '2025/12'].map(date => point('香水', date, 1))
})
assert.deepEqual(months.meta.field.values, ['2025/12', '2026/2', '2026/10'])
assert.equal(orderLineData(dateChart, { data: [] }).data.length, 0)

// Execute the real options pipeline so points appended by the existing empty
// value strategy are sorted as well, rather than sorting only the query rows.
const source = fs.readFileSync(path.join(base, 'line.ts'), 'utf8')
const ast = ts.createSourceFile('line.ts', source, ts.ScriptTarget.Latest, true)
const declaration = ast.statements.find(ts.isClassDeclaration)
const method = declaration.members.find(n => n.name?.getText(ast) === 'setupOptions')
const setup = new Function(
  'flow',
  'orderLineData',
  transpile('return function(chart, options) ' + method.body.getText(ast))
)(
  (...steps) =>
    (chart, options) =>
      steps.reduce((result, step) => step(chart, result), options),
  orderLineData
)
const config = {}
for (const match of method.body.getText(ast).matchAll(/this\.(\w+)/g)) {
  config[match[1]] = (_chart, options) => options
}
config.configEmptyDataStrategy = (_chart, options) => ({
  ...options,
  data: [...options.data, point('身体乳', '2026-09-07', null)]
})
const result = setup.call(config, dateChart, input)
assert.deepEqual(
  result.data.filter(p => p.category === '身体乳').map(p => p.field),
  ['2026-08-07', '2026-09-07', '2026-09-09']
)
console.log(
  'lineOrder: chronological domain, per-series paths, explicit sorting and empty-value pipeline passed'
)

// Use the actual shared empty-data implementation and the line override, so
// both query nulls and nulls inserted for missing date/series pairs are covered.
const utilsFile = path.resolve(base, '../../../util.ts')
const utilsAst = ts.createSourceFile(
  'util.ts',
  fs.readFileSync(utilsFile, 'utf8'),
  ts.ScriptTarget.Latest,
  true
)
const emptyNames = new Set([
  'handleEmptyDataStrategy',
  'handleBreakLineMultiDimension',
  'handleSetZeroMultiDimension',
  'handleSetZeroSingleDimension',
  'handleIgnoreData'
])
const emptySource = utilsAst.statements
  .filter(n => ts.isFunctionDeclaration(n) && emptyNames.has(n.name.text))
  .map(n => n.getText(utilsAst))
  .join('\n')
const utilsMod = new Module(utilsFile, module)
utilsMod._compile(
  transpile(
    'function parseJson(value) { return typeof value === "string" ? JSON.parse(value) : value }\n' +
      emptySource
  ),
  utilsFile
)
const emptyMethod = declaration.members.find(
  n => n.name?.getText(ast) === 'configEmptyDataStrategy'
)
const LineTest = new Function(
  'Base',
  transpile('return class extends Base { ' + emptyMethod.getText(ast) + ' }')
)(
  class {
    configEmptyDataStrategy(chart, options) {
      return utilsMod.exports.handleEmptyDataStrategy(chart, options)
    }
  }
)
const sparse = {
  ...input,
  data: [
    point('A', '2026-08-16', 10),
    point('B', '2026-08-17', 0),
    point('A', '2026-08-18', 30),
    point('A', '2026-08-19', null)
  ]
}
for (const strategy of ['breakLine', 'ignoreData', 'setZero']) {
  const chart = {
    ...dateChart,
    type: 'line',
    yAxis: [{}],
    xAxisExt: [{}],
    senior: JSON.stringify({ functionCfg: { emptyDataStrategy: strategy } })
  }
  const line = new LineTest()
  config.configEmptyDataStrategy = line.configEmptyDataStrategy
  const result = setup.call(config, chart, structuredClone(sparse))
  assert.equal(result.connectNulls, true)
  assert.equal(result.data.find(p => p.category === 'B' && p.field === '2026-08-17').value, 0)
  const a = result.data.filter(p => p.category === 'A')
  assert.deepEqual(
    a.filter(p => p.value != null && p.value !== 0).map(p => p.value),
    [10, 30]
  )
  if (strategy === 'breakLine') {
    assert.equal(a.find(p => p.field === '2026-08-17').value, null)
    assert.equal(a.find(p => p.field === '2026-08-19').value, null)
  } else if (strategy === 'setZero') {
    assert.equal(a.find(p => p.field === '2026-08-17').value, 0)
  } else {
    assert.deepEqual(
      a.map(p => p.field),
      ['2026-08-16', '2026-08-18']
    )
  }
}
console.log(
  'line gaps: real empty-value pipeline connects valid neighbours without replacing nulls; explicit zero strategy preserved'
)
