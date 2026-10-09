const assert = require('node:assert/strict')
const fs = require('node:fs')
const ts = require('typescript')
const moduleCopy = { exports: {} }
new Function(
  'module',
  'exports',
  ts.transpileModule(fs.readFileSync(__dirname + '/chartDisplayLimit.ts', 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText
)(moduleCopy, moduleCopy.exports)
const { displayCountPatch, activeSortFields, displayLimitReason } = moduleCopy.exports
const chart = {
  type: 'bar',
  xAxis: [{ id: 1, name: '门店', sort: 'none' }],
  yAxis: [
    { id: 2, fieldInstanceId: 'sum', summary: 'sum', sort: 'desc' },
    { id: 2, fieldInstanceId: 'avg', summary: 'avg', sort: 'asc' }
  ],
  sortPriority: [
    { id: 2, axis: 'yAxis', fieldInstanceId: 'avg' },
    { id: 2, axis: 'yAxis', fieldInstanceId: 'sum' }
  ]
}
const original = JSON.stringify(chart)
assert.deepEqual(
  activeSortFields(chart).map(f => f.fieldInstanceId),
  ['avg', 'sum']
)
assert.equal(JSON.stringify(chart), original)
for (const n of [20, 50, 7]) {
  const patch = displayCountPatch(n)
  assert.deepEqual(patch, { resultMode: 'custom', resultCount: n })
  assert.deepEqual({ ...chart, ...patch }.sortPriority, chart.sortPriority)
}
assert.deepEqual(displayCountPatch(), { resultMode: 'all' })
for (const n of [0, -1, 2.5, NaN, 1000001]) assert.throws(() => displayCountPatch(n))
assert.equal(
  activeSortFields({ ...chart, yAxis: chart.yAxis.map(f => ({ ...f, sort: 'none' })) }).length,
  0
)
const legacy = {
  ...chart,
  sortPriority: [{ id: 1 }, { id: 2 }],
  xAxis: [{ id: 1, sort: 'custom_sort' }]
}
assert.equal(activeSortFields(legacy)[0].axis, 'xAxis')
assert.equal(activeSortFields(JSON.parse(JSON.stringify(chart)))[0].fieldInstanceId, 'avg')
for (const extra of [
  { xAxisExt: [{ id: 3 }] },
  { extStack: [{ id: 3 }] },
  { xAxis: [] },
  { customAttr: { basicStyle: { calcTopN: true } } }
])
  assert.ok(displayLimitReason({ ...chart, ...extra }))
assert.ok(displayLimitReason({ ...chart, type: 'chart-mix' }))
const mix = { ...chart, type: 'chart-mix', yAxis: [chart.yAxis[0]] }
assert.equal(displayLimitReason(mix), '')
const component = fs.readFileSync(__dirname + '/../editor/SortAndLimit.vue', 'utf8')
const script = component.split('<script setup lang="ts">')[1].split('</script>')[0]
const tree = ts.createSourceFile('SortAndLimit.ts', script, ts.ScriptTarget.Latest, true)
const functions = tree.statements
  .filter(ts.isFunctionDeclaration)
  .map(n => n.getText(tree))
  .join('\n')
const count = { value: 50 },
  customRequested = { value: false }
let emitted
const handler = new Function(
  'count',
  'customRequested',
  'emit',
  'displayCountPatch',
  ts.transpileModule(functions, { compilerOptions: { target: ts.ScriptTarget.ES2020 } })
    .outputText + '; return changeCount;'
)(
  count,
  customRequested,
  (_event, p) => {
    emitted = p
  },
  displayCountPatch
)
handler('20')
assert.deepEqual(emitted, { resultMode: 'custom', resultCount: 20 })
handler('custom')
assert.equal(customRequested.value, true)
assert.equal(emitted.resultCount, 50)
handler('all')
assert.deepEqual(emitted, { resultMode: 'all' })
assert.equal(component.includes('aria-label="排序字段"'), false)
assert.equal(component.includes('恢复默认'), false)
console.log(
  'PASS: count controls preserve sort, active-only priorities, placement identity, legacy order, combo guards and production UI handlers'
)
