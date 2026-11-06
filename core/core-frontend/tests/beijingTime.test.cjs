const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const test = require('node:test')
const assert = require('node:assert/strict')
const ts = require('typescript')
const { parse, compileScript, compileTemplate } = require('@vue/compiler-sfc')
const source = fs.readFileSync(path.resolve(__dirname, '../src/utils/beijingTime.ts'), 'utf8')
function load() {
  const context = { exports: {}, Intl, Date }
  vm.runInNewContext(ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText, context)
  return context.exports.formatBeijingTime
}
test('UTC SQL records, ISO records and epoch milliseconds all display Beijing time', () => {
  const format = load()
  for (const input of ['2026-11-06 01:02:03.123', '2026-11-06T01:02:03.123',
    '2026-11-06T01:02:03.123Z', '2026-11-06T09:02:03+08:00',
    Date.parse('2026-11-06T01:02:03Z')]) assert.equal(format(input), '2026-11-06 09:02:03')
  assert.equal(format('2026-12-31 20:05:00'), '2027-01-01 04:05:00')
  assert.equal(format('2026-11-05T16:00:00Z'), '2026-11-06 00:00:00')
})
test('display is independent of browser timezone, missing records and business dates stay valid', () => {
  const previous = process.env.TZ
  try {
    for (const zone of ['UTC', 'America/Los_Angeles', 'Asia/Shanghai']) {
      process.env.TZ = zone
      const format = load()
      assert.equal(format('2026-11-06 01:02:03'), '2026-11-06 09:02:03')
      assert.equal(format(null), '—')
      assert.equal(format(undefined, '等待首次同步'), '等待首次同步')
      assert.equal(format('2026-11-06'), '2026-11-06')
      assert.equal(format('invalid'), 'invalid')
      assert.equal(format(0), '1970-01-01 08:00:00')
    }
  } finally {
    if (previous === undefined) delete process.env.TZ
    else process.env.TZ = previous
  }
})
test('all updated screens import the formatter and compile their scripts and templates', () => {
  for (const file of ['views/visualized/data/datasource/DataUpdateDrawer.vue',
    'views/visualized/data/datasource/DataUpdateWorkspace.vue',
    'views/visualized/data/datasource/FeishuSheetWizard.vue',
    ...['users', 'audit', 'feishu'].map(name => `views/company/admin/${name}/index.vue`)]) {
    const text = fs.readFileSync(path.resolve(__dirname, '../src', file), 'utf8')
    assert.ok(text.includes("from '@/utils/beijingTime'"), file)
    const { descriptor, errors } = parse(text)
    assert.deepEqual(errors, [])
    const script = compileScript(descriptor, { id: file })
    const template = compileTemplate({ source: descriptor.template.content, filename: file,
      id: file, compilerOptions: { bindingMetadata: script.bindings } })
    assert.deepEqual(template.errors, [], file)
    assert.equal(script.bindings.formatBeijingTime, 'setup-maybe-ref', file)
  }
})
