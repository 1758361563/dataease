const fs = require('node:fs')
const path = require('node:path')
const Module = require('node:module')
const ts = require('typescript')
const assert = require('node:assert/strict')
const vue = require('vue')
const { renderToString } = require('@vue/server-renderer')
const { parse, compileTemplate } = require('@vue/compiler-sfc')
const { baseParse, NodeTypes } = require('@vue/compiler-dom')
const transpile = code =>
  ts.transpileModule(code, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText
const base = path.resolve(__dirname, '../src/views/chart/components/editor/drag-item')
const defaultsFile = path.join(base, 'components/compare.ts')
const defaultsModule = new Module(defaultsFile, module)
defaultsModule._compile(transpile(fs.readFileSync(defaultsFile, 'utf8')), defaultsFile)
const { createCompareCalc } = defaultsModule.exports
const first = createCompareCalc(),
  second = createCompareCalc()
first.custom.currentTimeRange.push('2026-01-01')
assert.deepEqual(second.custom.currentTimeRange, [])
assert.equal(createCompareCalc(null).type, 'none')
assert.equal(createCompareCalc({ type: 'percent', percentScope: 'column' }).percentScope, 'column')
const file = path.join(base, 'QuotaItem.vue')
const { descriptor } = parse(fs.readFileSync(file, 'utf8'))
const ast = ts.createSourceFile(
  'quota.ts',
  descriptor.scriptSetup.content,
  ts.ScriptTarget.Latest,
  true
)
const names = new Set(['compareType', 'ensureCompareCalc', 'quickCalc', 'editCompare'])
const functions = ast.statements
  .filter(ts.isVariableStatement)
  .flatMap(node => Array.from(node.declarationList.declarations))
  .filter(node => names.has(node.name.getText(ast)))
  .map(node => `const ${node.getText(ast)}`)
  .join('\n')
function handlers(field, type = 'table-pivot') {
  const item = vue.ref(field),
    chart = vue.ref({ type }),
    events = []
  let opened = 0
  const api = new Function(
    'computed',
    'item',
    'chart',
    'createCompareCalc',
    'resetValueFormatter',
    'emit',
    'openPercentScope',
    'props',
    transpile(functions + '\nreturn {compareType,quickCalc,editCompare}')
  )(
    vue.computed,
    item,
    chart,
    createCompareCalc,
    () => {},
    (...event) => events.push(event),
    () => opened++,
    { type: 'quota', index: 0 }
  )
  return { api, item, chart, events, opened: () => opened }
}
let menu
function find(node) {
  if (
    node.type === NodeTypes.ELEMENT &&
    node.tag === 'el-dropdown' &&
    node.props.some(
      prop =>
        prop.type === NodeTypes.DIRECTIVE &&
        prop.name === 'on' &&
        prop.arg?.content === 'command' &&
        prop.exp?.content === 'quickCalc'
    )
  )
    menu = node
  for (const child of node.children || []) find(child)
}
find(baseParse(descriptor.template.content))
assert.ok(menu, 'quick-calculation submenu must exist')
const compiled = compileTemplate({
  source: menu.loc.source,
  filename: file,
  id: 'quick-calc-regression'
})
assert.deepEqual(compiled.errors, [])
const renderModule = new Module(file, module)
renderModule.require = name => require(name)
renderModule._compile(transpile(compiled.code), file)
const stub = vue.defineComponent({
  props: ['command', 'disabled'],
  setup: (_, ctx) => () => vue.h('div', [ctx.slots.default?.(), ctx.slots.dropdown?.()])
})
async function main() {
  for (const compareCalc of [null, undefined, {}, { type: null }, { type: 'percent' }]) {
    const field = { compareCalc, formatterCfg: { type: 'auto' } },
      before = JSON.stringify(field)
    const ctx = handlers(field)
    const app = vue.createSSRApp({
      render: renderModule.exports.render,
      setup: () => ({
        item: ctx.item,
        compareType: ctx.api.compareType,
        chart: ctx.chart,
        quickCalc: ctx.api.quickCalc,
        t: value => value,
        beforeQuickCalc: type => ({ type }),
        state: { disableEditCompare: false, notSupportAccumulateViews: [], quotaViews: [] },
        themes: 'light',
        yoyLabel: [],
        percentScopeLabel: '全表',
        notSupportAccumulateViews: []
      })
    })
    for (const name of [
      'el-dropdown',
      'el-dropdown-menu',
      'el-dropdown-item',
      'el-icon',
      'Icon',
      'icon_right_outlined',
      'icon_done_outlined'
    ])
      app.component(name, stub)
    const html = await renderToString(app)
    for (const text of ['chart.none', 'chart.yoy_label', 'chart.percent', 'chart.accumulate'])
      assert.ok(html.includes(text), `Missing ${text}`)
    assert.equal(
      JSON.stringify(field),
      before,
      'opening submenu must not change the field configuration'
    )
  }
  let ctx = handlers({ compareCalc: null, formatterCfg: { type: 'auto' } })
  ctx.api.quickCalc({ type: 'percent' })
  assert.equal(ctx.opened(), 1)
  assert.equal(
    ctx.item.value.compareCalc,
    null,
    'opening the percent dialog must remain cancellable'
  )
  ctx.api.quickCalc({ type: 'accumulate' })
  assert.equal(ctx.item.value.compareCalc.type, 'accumulate')
  ctx.api.quickCalc({ type: 'none' })
  assert.equal(ctx.item.value.compareCalc.type, 'none')
  ctx = handlers({ compareCalc: null, formatterCfg: { type: 'auto' } }, 'bar')
  ctx.api.quickCalc({ type: 'percent' })
  assert.equal(ctx.item.value.compareCalc.type, 'percent')
  ctx = handlers({ compareCalc: null, formatterCfg: { type: 'auto' } })
  ctx.api.quickCalc({ type: 'setting' })
  assert.ok(ctx.item.value.compareCalc.custom)
  assert.equal(ctx.events[0][0], 'editItemCompare')
  console.log(
    'Quick-calculation submenu: actual Vue rendering for null/legacy/percent configs, no render mutation, independent defaults and all selection handlers passed'
  )
}
main().catch(error => {
  console.error(error)
  process.exitCode = 1
})
