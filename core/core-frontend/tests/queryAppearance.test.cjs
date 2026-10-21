const fs = require('node:fs'),
  path = require('node:path'),
  Module = require('node:module')
const assert = require('node:assert/strict'),
  ts = require('typescript')
const { parse, compileScript, compileTemplate, compileStyleAsync } = require('@vue/compiler-sfc')
const file = path.resolve(__dirname, '../src/utils/queryAppearance.ts')
const mod = new Module(file)
mod.paths = module.paths
mod._compile(
  ts.transpileModule(fs.readFileSync(file, 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS }
  }).outputText,
  file
)
const { migrateQueryAppearance, readableQuerySize } = mod.exports
for (const scale of [0.5, 0.75, 0.8, 1]) {
  assert.equal(readableQuerySize(13, 13, scale) * scale, 13)
  assert.equal(readableQuerySize(32, 32, scale) * scale, 32)
  assert.equal(readableQuerySize(12, 12, scale) * scale, 12)
  assert.ok(readableQuerySize(180, 150, scale) * scale >= 150)
}
assert.equal(readableQuerySize(13, 13, 1.5), 13, 'zooming in remains unchanged')
assert.equal(readableQuerySize(18, 13, 0.8), 18, 'larger custom fonts are retained')
assert.equal(readableQuerySize(10, 13, 0.8), 10, 'explicit compact fonts remain compact')
assert.equal(readableQuerySize(13, 13, 0.5, false), 13, 'mobile/big-screen scaling stays unchanged')
assert.equal(readableQuerySize(13, 13, 0), 13)
assert.equal(readableQuerySize(13, 13, NaN), 13)
const legacy = {
  fontSize: '16',
  placeholderSize: 16,
  queryConditionHeight: 36,
  queryConditionWidth: 227,
  nameboxSpacing: 8,
  queryConditionSpacing: 16,
  labelColor: 'rgba(31, 35, 41, 1)',
  text: '',
  borderColor: '',
  btnList: ['reset', 'clear', 'sure'],
  layout: 'horizontal'
}
const style = structuredClone(legacy),
  fields = [
    { id: 'date', queryConditionWidth: 227, selectValue: ['2026-01-01', '2026-10-15'] },
    { id: 'brand', queryConditionWidth: 300, selectValue: ['品牌A'] }
  ]
const values = fields.map(f => structuredClone(f.selectValue))
migrateQueryAppearance(style, fields)
assert.equal(style.fontSize, '12')
assert.equal(style.placeholderSize, 13)
assert.equal(style.queryConditionHeight, 32)
assert.equal(style.queryConditionWidth, 180)
assert.equal(style.nameboxSpacing, 6)
assert.equal(style.labelColor, '#64748B')
assert.equal(fields[0].queryConditionWidth, 180)
assert.equal(fields[1].queryConditionWidth, 300)
assert.deepEqual(
  fields.map(f => f.selectValue),
  values
)
assert.deepEqual(style.btnList, legacy.btnList)
assert.equal(style.layout, 'horizontal')
style.fontSize = '16'
style.queryConditionWidth = 227
fields[0].queryConditionWidth = 227
const saved = JSON.stringify([style, fields])
migrateQueryAppearance(style, fields)
assert.equal(JSON.stringify([style, fields]), saved, 'manual edits after migration survive')
const custom = {
  fontSize: '20',
  placeholderSize: 18,
  queryConditionHeight: 44,
  queryConditionWidth: 0,
  nameboxSpacing: 0,
  queryConditionSpacing: 24,
  labelColor: '#FFFFFF',
  borderColor: '#123456',
  text: '#abcdef',
  bgColor: '#131c42',
  layout: 'vertical',
  labelShow: false
}
const before = structuredClone(custom)
migrateQueryAppearance(custom)
for (const [key, value] of Object.entries(before)) assert.deepEqual(custom[key], value, key)
async function main() {
  for (const relative of [
    'custom-component/v-query/Component.vue',
    'views/chart/components/editor/editor-style/VQueryChartStyle.vue',
    'custom-component/v-query/Time.vue',
    'custom-component/v-query/Select.vue',
    'custom-component/v-query/NumberInput.vue',
    'custom-component/v-query/TextSearch.vue',
    'custom-component/v-query/Tree.vue'
  ]) {
    const filename = path.resolve(__dirname, '../src', relative),
      source = fs.readFileSync(filename, 'utf8')
    const { descriptor, errors } = parse(source, { filename })
    assert.deepEqual(errors, [])
    const script = compileScript(descriptor, {
      id: 'query-appearance',
      babelParserPlugins: ['typescript', 'jsx']
    })
    const template = compileTemplate({
      source: descriptor.template.content,
      filename,
      id: 'query-appearance',
      compilerOptions: { bindingMetadata: script.bindings }
    })
    assert.deepEqual(template.errors, [], relative)
    for (const style of descriptor.styles) {
      const result = await compileStyleAsync({
        source: style.content,
        filename,
        id: 'data-v-query-appearance',
        scoped: style.scoped,
        preprocessLang: style.lang,
        preprocessOptions: { globalVars: { 'dv-canvas-main-font-color': '#1f2329' } }
      })
      assert.deepEqual(result.errors, [], relative)
    }
  }
  console.log(
    'Filter defaults/custom settings, persisted widths, values/buttons, and all changed Vue/Less compilation passed'
  )
}
main().catch(error => {
  console.error(error)
  process.exit(1)
})
