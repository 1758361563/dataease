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
  new Function('module', 'exports', 'require', output)(loaded, loaded.exports, load)
  return loaded.exports
}
const {
  shiftDateRange,
  resolveDateComparison,
  cloneViewWithShiftedDateFilter,
  computeCompareResult
} = load('./guandataDateFilterCompare')
const ms = value => Date.parse(value + '+08:00')
const range = (a, b) => [ms(a), ms(b)]
assert.deepEqual(
  shiftDateRange(...range('2026-03-01T00:00:00', '2026-03-31T23:59:59'), 'month_yoy'),
  range('2026-02-01T00:00:00', '2026-02-28T23:59:59')
)
assert.deepEqual(
  shiftDateRange(...range('2026-04-01T00:00:00', '2026-04-30T23:59:59'), 'month_mom'),
  range('2026-03-01T00:00:00', '2026-03-30T23:59:59')
)
assert.deepEqual(
  shiftDateRange(...range('2024-02-29T00:00:00', '2024-02-29T23:59:59'), 'year_yoy'),
  range('2023-02-28T00:00:00', '2023-02-28T23:59:59')
)
assert.equal(shiftDateRange(Infinity, Infinity, 'day_mom'), null)
const broad = range('2026-09-01T00:00:00', '2026-09-30T23:59:59')
const narrow = range('2026-09-03T00:00:00', '2026-09-08T23:59:59')
const view = {
  chartExtRequest: {
    filter: [
      { fieldId: 'date,other-date', operator: 'between', value: broad },
      { fieldId: 'shop', operator: 'in', value: ['A'] }
    ],
    outerParamsFilters: [{ fieldId: 'date', operator: 'between', value: narrow }]
  }
}
const original = JSON.stringify(view)
const customView = {
  ...view,
  customFilter: {
    logic: 'and',
    items: [
      { type: 'item', fieldId: 'date', filterType: 'logic', term: 'eq', value: '2026-09-05' },
      { type: 'item', fieldId: 'shop', filterType: 'logic', term: 'eq', value: 'A' }
    ]
  }
}
assert.deepEqual(resolveDateComparison(customView, 'date', 'month_yoy').current, [
  ms('2026-09-05T00:00:00'),
  ms('2026-09-05T23:59:59') + 999
])
const customShifted = cloneViewWithShiftedDateFilter(customView, 'date', 'month_yoy')
assert.equal(customShifted.customFilter.items.length, 1)
assert.equal(customShifted.customFilter.items[0].fieldId, 'shop')
assert.equal(customView.customFilter.items.length, 2)
assert.equal(
  resolveDateComparison(
    { ...customView, customFilter: { ...customView.customFilter, logic: 'or' } },
    'date',
    'month_yoy'
  ).status,
  'unsupported'
)
assert.equal(
  resolveDateComparison(
    {
      customFilter: {
        logic: 'and',
        items: [{ fieldId: 'date', filterType: 'logic', term: 'eq', value: '2026-02-30' }]
      }
    },
    'date',
    'month_yoy'
  ).status,
  'unsupported'
)
assert.deepEqual(resolveDateComparison(view, 'date', 'month_yoy').current, narrow)
const shifted = cloneViewWithShiftedDateFilter(view, 'date', 'month_yoy')
assert.equal(JSON.stringify(view), original)
assert.deepEqual(shifted.chartExtRequest.filter[0], {
  fieldId: 'other-date',
  operator: 'between',
  value: broad
})
assert.deepEqual(shifted.chartExtRequest.filter[2], view.chartExtRequest.filter[1])
assert.deepEqual(
  resolveDateComparison(shifted, 'date', 'month_yoy').current,
  range('2026-08-03T00:00:00', '2026-08-08T23:59:59')
)
assert.equal(resolveDateComparison({}, 'date', 'month_yoy').status, 'missing')
const withFilters = filter => ({ chartExtRequest: { filter } })
assert.equal(
  resolveDateComparison(
    withFilters([{ fieldId: 'date', operator: 'in', value: [1, 2] }]),
    'date',
    'day_mom'
  ).status,
  'unsupported'
)
assert.equal(
  resolveDateComparison(
    withFilters([{ fieldId: 'date', operator: 'between', value: [2, 1] }]),
    'date',
    'day_mom'
  ).status,
  'empty'
)
assert.equal(
  resolveDateComparison(
    withFilters([{ fieldId: 'date', operator: 'ge', value: [1] }]),
    'date',
    'day_mom'
  ).status,
  'invalid'
)
assert.deepEqual(
  resolveDateComparison(
    withFilters([
      { fieldId: 'date', operator: 'ge', value: [1] },
      { fieldId: 'date', operator: 'le', value: [10] }
    ]),
    'date',
    'day_mom'
  ).current,
  [1, 10]
)
assert.equal(computeCompareResult(null, 12, 'pre'), 12)
assert.equal(computeCompareResult(null, 12, 'percent'), null)
assert.equal(computeCompareResult(0, 0, 'percent'), null)
assert.equal(computeCompareResult(10, 0, 'percent'), null)
assert.equal(computeCompareResult(0, 10, 'percent'), -1)
assert.equal(computeCompareResult(-5, -10, 'percent'), -0.5)
assert.equal(computeCompareResult(10, Infinity, 'pre'), null)
console.log('Date comparison production-module tests passed; TZ=' + process.env.TZ)
