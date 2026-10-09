const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const ts = require('typescript')
const source = fs.readFileSync(path.join(__dirname, 'tooltipFieldIdentity.ts'), 'utf8')
const compiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
}).outputText
const loaded = { exports: {} }
new Function('module', 'exports', compiled)(loaded, loaded.exports)
const { visibleTooltipField, findTooltipField, reconcileTooltipFields } = loaded.exports
const sum = {
  id: '7',
  fieldInstanceId: 'sum',
  summary: 'sum',
  axisType: 'yAxis',
  show: true,
  name: 'Sum'
}
const avg = {
  id: '7',
  fieldInstanceId: 'avg',
  summary: 'avg',
  axisType: 'yAxisExt',
  show: true,
  name: 'Average'
}
assert.equal(visibleTooltipField([sum, avg], sum), sum)
assert.equal(visibleTooltipField([sum, avg], avg), avg)
assert.equal(visibleTooltipField([sum, { ...avg, show: false }], avg), undefined)
assert.equal(findTooltipField([sum, avg], { id: '7' }), undefined)
assert.equal(findTooltipField([sum, avg], { id: '7', fieldInstanceId: 'missing' }), undefined)
assert.equal(
  visibleTooltipField([sum, avg], { fieldId: '7', fieldInstanceId: 'avg', summary: 'avg' }),
  avg
)
const legacy = { id: '7', seriesId: '7-yAxisExt', show: true }
assert.equal(findTooltipField([legacy], avg, 'yAxisExt'), legacy)
assert.equal(findTooltipField([legacy], sum, 'yAxis'), undefined)
const old = { id: '7', show: false }
assert.equal(visibleTooltipField([old], sum), undefined)
const saved = [sum, { ...avg, show: false }]
const snapshot = JSON.stringify(saved)
const automatic = reconcileTooltipFields([], [sum, avg], [{ id: '8' }])
assert.deepEqual(
  automatic.map(field => field.show),
  [true, true, false]
)
assert.deepEqual(
  automatic.slice(0, 2).map(field => field.seriesId),
  ['sum', 'avg']
)
const custom = reconcileTooltipFields(saved, [sum, avg], [{ id: '8' }])
assert.equal(custom[1].show, false)
assert.equal(JSON.stringify(saved), snapshot)
assert.deepEqual(reconcileTooltipFields(custom, [sum, avg], [{ id: '8' }]), custom)
// Serialized legacy numeric ids and numeric result ids must still match.
assert.equal(findTooltipField([{ id: 7, show: true }], { fieldId: '7' }).id, 7)
console.log(
  'PASS: placement isolation, hidden fields, legacy axis fallback, ambiguity, dynamic values, automatic editor state and round trip'
)
