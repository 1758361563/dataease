const fs = require('node:fs')
const path = require('node:path')
const Module = require('node:module')
const assert = require('node:assert/strict')
const ts = require('typescript')
const { parse, compileScript, compileTemplate } = require('@vue/compiler-sfc')
const file = path.resolve(__dirname, '../src/views/chart/components/js/chartFieldInstance.ts')
const mod = new Module(file)
mod._compile(
  ts.transpileModule(fs.readFileSync(file, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText,
  file
)
const { formatPivotDate: format } = mod.exports
const day = { deType: 1, dateStyle: 'y_M_d', dateShowFormat: 'weekday_name' }
assert.equal(format('2026-10-13', day), '星期二')
assert.equal(format('2026/10/12', day), '星期一')
assert.equal(format('2026-10-11', day), '星期日')
assert.equal(format('2026-02-31', day), '2026-02-31')
assert.equal(format('2026-10-13', { ...day, dateShowFormat: undefined }), '2026-10-13')
const week = { deType: 1, dateStyle: 'y_W' }
assert.equal(format('2026-10-12', week), '2026年第42周')
assert.equal(format('2025-12-29', week), '2026年第1周')
assert.equal(format('2020-12-28', week), '2020年第53周')
assert.equal(
  format('2025-12-29', { ...week, dateShowFormat: 'week_range' }),
  '[2025-12-29, 2026-01-04]'
)
assert.equal(format(null, week), null)
assert.equal(format('invalid', week), 'invalid')
assert.equal(format('2026-02-31', week), '2026-02-31')
const month = { deType: 1, dateStyle: 'y_M', dateShowFormat: 'month_name' }
assert.equal(format('2026-07', month), '7月')
assert.equal(format('2025/07', month), '7月')
assert.equal(format('2026-07', { ...month, dateShowFormat: 'month_number' }), '07')
assert.equal(format('2026-07', { ...month, dateShowFormat: undefined }), '2026-07')
assert.equal(format('07', { deType: 1, dateStyle: 'M' }), '7月')
const component = fs.readFileSync(
  path.resolve(__dirname, '../src/views/chart/components/editor/drag-item/DimensionItem.vue'),
  'utf8'
)
const { descriptor, errors } = parse(component)
assert.deepEqual(errors, [])
const script = compileScript(descriptor, { id: 'date-options' })
const template = compileTemplate({
  source: descriptor.template.content,
  filename: 'DimensionItem.vue',
  id: 'date-options',
  compilerOptions: { bindingMetadata: script.bindings }
})
assert.deepEqual(template.errors, [])
console.log('Pivot date formatting and Vue compilation passed')
