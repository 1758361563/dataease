const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const ts = require('typescript')
const { parse, compileScript, compileTemplate } = require('@vue/compiler-sfc')
const dir = path.join(__dirname, '../src/views/visualized/data/datasource')
const source = fs.readFileSync(path.join(dir, 'updateSchedule.ts'), 'utf8')
const moduleValue = { exports: {} }
new Function('exports', ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText)(moduleValue.exports)
const { scheduleRows, scheduleStrategy } = moduleValue.exports
function task(id, dependencies = [], extra = {}) {
  return { definition: { id, table: id, enabled: true, trigger: dependencies.length ? 'DEPENDENCY' : 'CRON', dependencies, mode: 'QUERY', strategy: 'FULL', ...extra }, effective: true }
}
const time = '2026-11-05T16:00:00Z'
const plan = id => ({ id, times: { values: [time], truncated: false }, effective: true })
const tasks = [task('a'), task('b', ['a']), task('c', ['b']), task('disabled', ['a'], { enabled: false }), task('g1', [], { enabled: false }), task('g2', ['g1'])]
const groups = [{ id: 'group', name: 'Group', tasks: ['g2', 'g1'], enabled: true }]
const rows = scheduleRows(tasks, groups, { tasks: [plan('a'), plan('g1')], groups: [plan('group')] })
assert.deepEqual(rows.map(row => row.task.definition.id), ['a', 'b', 'c', 'g1', 'g2'])
assert.deepEqual(rows.map(row => row.dependent), [false, true, true, false, true])
assert.equal(new Set(rows.map(row => row.key)).size, rows.length)
assert.equal(scheduleStrategy(task('script', [], { mode: 'SCRIPT' })), '脚本控制')
assert.equal(scheduleRows(tasks, groups, { tasks: [], groups: [] }).length, 0)
const inactive = plan('a'); inactive.effective = false
assert.ok(scheduleRows(tasks, groups, { tasks: [inactive], groups: [] }).every(row => !row.effective))
for (const file of ['DataUpdateSchedule.vue', 'DataUpdateWorkspace.vue']) {
  const { descriptor, errors } = parse(fs.readFileSync(path.join(dir, file), 'utf8'))
  assert.deepEqual(errors, [])
  const script = compileScript(descriptor, { id: file })
  const template = compileTemplate({ source: descriptor.template.content, filename: file, id: file, compilerOptions: { bindingMetadata: script.bindings } })
  assert.deepEqual(template.errors, [])
}
console.log('PASS schedule ordering, group ownership, dependency chains, disabled plans, strategy labels and Vue compilation')
