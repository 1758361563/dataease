// Run from core-frontend: node src/custom/company-bi/components/indicator-combo/breakdown.selftest.cjs
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const ts = require('typescript')

const source = fs.readFileSync(path.join(__dirname, 'breakdown.ts'), 'utf8')
const output = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
}).outputText
const loaded = { exports: {} }
new Function('module', 'exports', 'require', output)(loaded, loaded.exports, require)
const { resolveBreakdownSort, sortBreakdownCards } = loaded.exports

const cards = [
  { key: 'b', label: 'Series 10', rawValue: 0.54, payload: null },
  { key: 'a', label: 'Series 2', rawValue: 0.58, payload: null },
  { key: 'empty', label: 'Z empty', rawValue: null, payload: null },
  { key: 'zero', label: 'Y zero', rawValue: 0, payload: null }
]

assert.deepEqual(
  sortBreakdownCards(cards, { by: 'label', direction: 'asc' }).map(card => card.key),
  ['a', 'b', 'zero', 'empty']
)
assert.deepEqual(
  sortBreakdownCards(cards, { by: 'value', direction: 'desc' }).map(card => card.key),
  ['a', 'b', 'zero', 'empty']
)
assert.deepEqual(
  sortBreakdownCards(cards, { by: 'value', direction: 'asc' }).map(card => card.key),
  ['zero', 'b', 'a', 'empty']
)
assert.deepEqual(
  sortBreakdownCards(cards).map(card => card.key),
  cards.map(card => card.key)
)

const dimension = { id: 'dimension', sort: 'asc' }
const metric = { id: 'metric', sort: 'desc' }
assert.deepEqual(resolveBreakdownSort({ xAxis: [dimension], yAxis: [metric] }), {
  by: 'label',
  direction: 'asc'
})
assert.deepEqual(
  resolveBreakdownSort({
    xAxis: [dimension],
    yAxis: [metric],
    sortPriority: [{ id: 'metric' }, { id: 'dimension' }]
  }),
  { by: 'value', direction: 'desc' }
)

console.log('PASS: breakdown label/value sorting, null/zero handling and priority')
