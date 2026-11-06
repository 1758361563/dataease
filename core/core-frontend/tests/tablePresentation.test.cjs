const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const assert = require('node:assert/strict')
const ts = require('typescript')
const vm = require('node:vm')
const { parse, compileScript, compileTemplate, compileStyleAsync } = require('@vue/compiler-sfc')
const root = path.resolve(__dirname, '../src')
const read = name => fs.readFileSync(path.join(root, name), 'utf8')
const dialog = parse(read('components/visualization/UserViewEnlarge.vue')).descriptor
const ast = ts.createSourceFile(
  'dialog.ts',
  dialog.scriptSetup.content,
  ts.ScriptTarget.Latest,
  true
)
const names = ['toggleEnlargeSize', 'dialogInit']
const functions = ast.statements
  .filter(ts.isVariableStatement)
  .flatMap(node =>
    [...node.declarationList.declarations]
      .filter(item => names.includes(item.name.getText(ast)))
      .map(item => 'const ' + item.getText(ast))
  )
  .join('\n')

test('actual dialog opens full viewport, toggles window mode and resets on reopen without changing source', async () => {
  const ref = value => ({ value })
  const context = {
    enlargeFullscreen: ref(false),
    dialogShow: ref(false),
    optType: ref(null),
    sourceViewType: ref(null),
    detailsError: ref(false),
    viewInfo: ref(null),
    config: ref(null),
    state: {},
    deepCopy: value => structuredClone(value),
    nextTick: async callback => callback?.(),
    initWatermark() {},
    ChartCarouselTooltip: { paused() {} },
    useEmitt: () => ({ emitter: { emit() {} } }),
    assign: Object.assign,
    DETAIL_TABLE_ATTR: {},
    dataDetailsOpt() {}
  }
  vm.createContext(context)
  vm.runInContext(
    ts.transpileModule(functions + '\nglobalThis.api = { toggleEnlargeSize, dialogInit }', {
      compilerOptions: { target: ts.ScriptTarget.ES2020 }
    }).outputText,
    context
  )
  const view = { type: 'table-pivot', customStyle: { text: { show: true } } }
  const before = JSON.stringify(view)
  context.api.dialogInit({}, view, { id: 'fixture' }, 'enlarge')
  assert.equal(context.enlargeFullscreen.value, true)
  await context.api.toggleEnlargeSize()
  assert.equal(context.enlargeFullscreen.value, false)
  await context.api.toggleEnlargeSize()
  assert.equal(context.enlargeFullscreen.value, true)
  context.api.dialogInit({}, view, { id: 'fixture' }, 'details')
  assert.equal(context.enlargeFullscreen.value, false, 'data details keep the existing window mode')
  context.api.dialogInit({}, view, { id: 'fixture' }, 'enlarge')
  assert.equal(context.enlargeFullscreen.value, true)
  assert.equal(JSON.stringify(view), before)
})

test('changed Vue components compile and responsive dialog styles compile to CSS', async () => {
  for (const file of [
    'components/visualization/UserViewEnlarge.vue',
    'views/chart/components/editor/editor-style/ChartStyle.vue',
    'views/chart/components/editor/index.vue',
    'views/chart/components/views/components/ChartComponentS2.vue'
  ]) {
    const parsed = parse(read(file))
    assert.deepEqual(parsed.errors, [])
    const script = compileScript(parsed.descriptor, { id: file })
    assert.deepEqual(
      compileTemplate({
        source: parsed.descriptor.template.content,
        filename: file,
        id: file,
        compilerOptions: { bindingMetadata: script.bindings }
      }).errors,
      []
    )
  }
  for (const style of dialog.styles) {
    const compiled = await compileStyleAsync({
      source: style.content,
      filename: 'UserViewEnlarge.vue',
      id: 'data-v-presentation',
      scoped: style.scoped,
      preprocessLang: 'less'
    })
    assert.deepEqual(compiled.errors, [])
  }
})

test('dialog table resize rebuilds the cached chart instead of retaining old header widths', () => {
  const source = ts.createSourceFile(
    'table.ts',
    parse(read('views/chart/components/views/components/ChartComponentS2.vue')).descriptor
      .scriptSetup.content,
    ts.ScriptTarget.Latest,
    true
  )
  const resize = source.statements
    .filter(ts.isVariableStatement)
    .flatMap(node => [...node.declarationList.declarations])
    .find(item => item.name.getText(source) === 'resize')
  const renders = []
  const context = {
    showPosition: { value: 'viewDialog' },
    actualChart: { id: 'cached' },
    timer: null,
    clearTimeout() {},
    setTimeout() {
      throw Error('unexpected old size-only resize')
    },
    debounceRender: value => renders.push(value)
  }
  vm.createContext(context)
  vm.runInContext(
    ts.transpileModule('const ' + resize.getText(source) + '\nresize(1600, 900)', {
      compilerOptions: { target: ts.ScriptTarget.ES2020 }
    }).outputText,
    context
  )
  assert.deepEqual(renders, [false])
})
