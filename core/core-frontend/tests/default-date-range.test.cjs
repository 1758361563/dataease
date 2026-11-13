const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const ts = require('typescript')
const { parse, compileScript, compileTemplate } = require('@vue/compiler-sfc')
const folder = path.resolve(__dirname, '../src/custom-component/v-query')
const cache = new Map()
function load(file) {
  if (cache.has(file)) return cache.get(file)
  const module = { exports: {} }
  const code = ts.transpileModule(fs.readFileSync(file, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020, esModuleInterop: true }
  }).outputText
  new Function('require', 'module', 'exports', code)(name => name.startsWith('.')
    ? load(path.resolve(path.dirname(file), name + '.ts')) : require(name), module, module.exports)
  cache.set(file, module.exports)
  return module.exports
}
const { resolveDefaultRange, createRangeEndpoints } = load(path.join(folder, 'default-date-range.ts'))
const dayjs = require('dayjs')
const fixed = value => ({ mode: 'fixed', value })
const relative = overrides => ({ mode: 'relative', anchor: 'today', amount: 0, unit: 'day', direction: 'before', time: '00:00:00', ...overrides })
const config = (start, end, granularity = 'daterange') => ({ relativeToCurrentRange: 'custom', timeGranularityMultiple: granularity, rangeEndpoints: { start, end } })
const now = new Date(2026, 10, 12, 10, 30)
const format = range => range.map(d => dayjs(d).format('YYYY-MM-DD HH:mm:ss'))
const mixed = config(fixed('2026-01-01'), relative({ amount: 1 }))
assert.deepEqual(format(resolveDefaultRange(mixed, 'panel', now)), ['2026-01-01 00:00:00', '2026-11-11 23:59:59'])
assert.deepEqual(format(resolveDefaultRange(JSON.parse(JSON.stringify(mixed)), 'panel', new Date(2026, 10, 13))), ['2026-01-01 00:00:00', '2026-11-12 23:59:59'])
assert.deepEqual(format(resolveDefaultRange(config(relative({ anchor: 'monthStart' }), relative({ anchor: 'monthEnd' })), 'panel', new Date(2024, 1, 15))), ['2024-02-01 00:00:00', '2024-02-29 23:59:59'])
assert.deepEqual(format(resolveDefaultRange(config(relative({ amount: 1, unit: 'month' }), relative({ direction: 'after', amount: 1 })), 'panel', new Date(2026, 2, 31))), ['2026-02-28 00:00:00', '2026-04-01 23:59:59'])
assert.deepEqual(format(resolveDefaultRange(config(fixed('2025-06-12'), fixed('2026-06-12'), 'yearrange'), 'panel', now)), ['2025-01-01 00:00:00', '2026-12-31 23:59:59'])
assert.deepEqual(format(resolveDefaultRange(config(relative({ time: '08:30:00' }), fixed('2026-11-12 18:45:00'), 'datetimerange'), 'panel', now)), ['2026-11-12 08:30:00', '2026-11-12 18:45:00'])
for (const bad of [fixed(''), relative({ amount: -1 }), relative({ amount: 1.5 }), relative({ unit: 'invalid' })]) {
  assert.ok(Number.isNaN(+resolveDefaultRange(config(bad, relative()), 'panel', now)[0]))
}
assert.equal(createRangeEndpoints({ around: 'b', timeNum: 2, relativeToCurrentType: 'date' }).start.direction, 'after')
const legacy = { timeNum: 7, timeNumRange: 0, around: 'f', aroundRange: 'f', relativeToCurrentType: 'date', relativeToCurrentTypeRange: 'date', timeGranularityMultiple: 'daterange', timeGranularity: 'date', relativeToCurrentRange: 'custom' }
const upgraded = { ...legacy, rangeEndpoints: createRangeEndpoints(legacy) }
assert.deepEqual(format(resolveDefaultRange(legacy)), format(resolveDefaultRange(upgraded)))
for (const file of ['DateRangeEndpoints.vue', 'DynamicTimeRange.vue', 'ConditionDefaultConfiguration.vue', 'QueryConditionConfiguration.vue', 'Component.vue']) {
  const { descriptor, errors } = parse(fs.readFileSync(path.join(folder, file), 'utf8'), { filename: file })
  assert.deepEqual(errors, [])
  const script = compileScript(descriptor, { id: file })
  const template = compileTemplate({ source: descriptor.template.content, filename: file, id: file, compilerOptions: { bindingMetadata: script.bindings } })
  assert.deepEqual(template.errors, [], file)
}
console.log('PASS: mixed endpoints, reload rollover, leap year, month-end offsets, year/datetime boundaries, invalid values, legacy migration; 5 Vue components compiled')
