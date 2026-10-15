const fs = require('node:fs')
const path = require('node:path')
const Module = require('node:module')
const ts = require('typescript')
const assert = require('node:assert/strict')
const { parse, compileScript, compileTemplate } = require('@vue/compiler-sfc')
const filename = path.resolve(__dirname, '../src/utils/dashboardFormulaReferences.ts')
const loaded = new Module(filename, module)
loaded._compile(
  ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText,
  filename
)
const { displayFormulaReferences: display, normalizeFormulaReferences: normalize } = loaded.exports
const { highlightFormulaReferences: highlight } = loaded.exports
const config = {
  version: 1,
  timezone: 'Asia/Shanghai',
  parameters: [{ id: '1307161009391276033', name: 'MTD期初' }],
  datasetFields: [
    { id: '1308143991480520706', datasetGroupId: 'dataset1', name: 'MTD业绩' },
    { id: '1308143991480520707', datasetGroupId: 'dataset1', name: 'MTD同期业绩' },
    { id: '1308143991480520708', datasetGroupId: 'dataset2', name: 'MTD业绩' }
  ]
}
const publicFields = [
  { id: '1791617312385', datasetGroupId: 'dataset1', name: '取值时间' },
  {
    id: '1308143991480520706',
    datasetGroupId: 'dataset1',
    fieldScope: 'DASHBOARD',
    name: '旧字段名称'
  }
]
const formula = '[1308143991480520706]-[1308143991480520707]'
const before = JSON.stringify(config)
assert.equal(display(formula, config, 'dataset1', publicFields), '[MTD业绩]-[MTD同期业绩]')
assert.equal(
  normalize(display(formula, config, 'dataset1', publicFields), config, 'dataset1', publicFields),
  formula
)
assert.equal(normalize('[MTD业绩]', config, 'dataset2', publicFields), '[1308143991480520708]')
assert.equal(
  display('[1308143991480520708]', config, 'dataset1', publicFields),
  '[1308143991480520708]'
)
const period = 'if([1791617312385]>=[DVP.1307161009391276033],[1308143991480520706],0)'
assert.equal(
  display(period, config, 'dataset1', publicFields),
  'if([取值时间]>=[DYNAMIC_PARAMS.MTD期初],[MTD业绩],0)'
)
assert.equal(
  normalize(display(period, config, 'dataset1', publicFields), config, 'dataset1', publicFields),
  period
)
assert.equal(
  normalize('[MTD业绩]*2 + [未输入完成', config, 'dataset1', publicFields),
  '[1308143991480520706]*2 + [未输入完成'
)
for (const quoted of [
  "'[1308143991480520706]'",
  '"[1308143991480520706]"',
  "'a''[1308143991480520706]'",
  '`[1308143991480520706]`'
])
  assert.equal(display(quoted, config, 'dataset1', publicFields), quoted)
assert.equal(
  normalize("'[MTD业绩]' + [MTD业绩]", config, 'dataset1', publicFields),
  "'[MTD业绩]' + [1308143991480520706]"
)
assert.equal(
  display('[999999999999999999]', config, 'dataset1', publicFields),
  '[999999999999999999]'
)
assert.equal(JSON.stringify(config), before)
const reopened = JSON.parse(before)
reopened.datasetFields[0].name = '本月业绩'
reopened.parameters[0].name = '本月期初'
assert.equal(
  display(period, reopened, 'dataset1', publicFields),
  'if([取值时间]>=[DYNAMIC_PARAMS.本月期初],[本月业绩],0)'
)
assert.equal(
  normalize(
    display(period, reopened, 'dataset1', publicFields),
    reopened,
    'dataset1',
    publicFields
  ),
  period
)
const vueFile = path.resolve(__dirname, '../src/components/visualization/DashboardParameterBar.vue')
const { descriptor } = parse(fs.readFileSync(vueFile, 'utf8'))
compileScript(descriptor, { id: 'formula-reference-regression' })
const template = compileTemplate({
  source: descriptor.template.content,
  filename: vueFile,
  id: 'formula-reference-regression'
})
assert.deepEqual(template.errors, [])
const highlightFormula =
  "if([取值时间]>=[DYNAMIC_PARAMS.MTD期初],[MTD业绩],[未知字段]) + '[MTD业绩]'\n[未输入完成"
const segments = highlight(highlightFormula, config, 'dataset1', publicFields)
assert.equal(segments.map(segment => segment.text).join(''), highlightFormula)
assert.deepEqual(
  segments.filter(segment => segment.kind).map(segment => [segment.text, segment.kind]),
  [
    ['[取值时间]', 'dataset'],
    ['[DYNAMIC_PARAMS.MTD期初]', 'parameter'],
    ['[MTD业绩]', 'shared']
  ]
)
assert.equal(highlight('[1308143991480520706]', config, 'dataset1', publicFields)[0].kind, 'shared')
assert.equal(highlight('[1791617312385]', config, 'dataset1', publicFields)[0].kind, 'dataset')
assert.equal(
  highlight('[DVP.1307161009391276033]', config, 'dataset1', publicFields)[0].kind,
  'parameter'
)
assert.equal(
  highlight('[1308143991480520708]', config, 'dataset1', publicFields)[0].kind,
  undefined
)
assert.equal(highlight('[旧字段名称]', config, 'dataset1', publicFields)[0].kind, undefined)
assert.equal(highlight('[MTD业绩]', reopened, 'dataset1', publicFields)[0].kind, undefined)
assert.equal(highlight('[本月业绩]', reopened, 'dataset1', publicFields)[0].kind, 'shared')
assert.equal(highlight('[999999999999999999]', config, 'dataset1', publicFields)[0].kind, undefined)
const inputFile = path.resolve(
  __dirname,
  '../src/components/visualization/DashboardFormulaInput.vue'
)
const inputSource = fs.readFileSync(inputFile, 'utf8')
const inputDescriptor = parse(inputSource).descriptor
compileScript(inputDescriptor, { id: 'formula-highlight-input' })
assert.deepEqual(
  compileTemplate({
    source: inputDescriptor.template.content,
    filename: inputFile,
    id: 'formula-highlight-input'
  }).errors,
  []
)
assert.ok(!inputSource.includes('v-html'), 'formula text must never render as HTML')
// Exercise the real input handlers: composing Chinese text must not trigger ID normalization early.
const inputAst = ts.createSourceFile(
  'input.ts',
  inputDescriptor.scriptSetup.content,
  ts.ScriptTarget.Latest,
  true
)
const handlerNames = new Set(['syncScroll', 'changed', 'compositionEnd'])
const handlerSource = inputAst.statements
  .filter(node => ts.isVariableStatement(node))
  .flatMap(node => Array.from(node.declarationList.declarations))
  .filter(node => handlerNames.has(node.name.getText(inputAst)))
  .map(node => `const ${node.getText(inputAst)}`)
  .join('\n')
const handlerCode = ts.transpileModule(
  `let composing=false; ${handlerSource}; return {changed,compositionEnd,start:()=>{composing=true}}`,
  { compilerOptions: { target: ts.ScriptTarget.ES2020 } }
).outputText
const draft = { value: '' },
  input = { value: { clientWidth: 400, scrollTop: 108, scrollLeft: 0 } },
  overlay = { value: { scrollTop: 0, scrollLeft: 0 } },
  width = { value: 0 },
  events = []
const handlers = new Function(
  'draft',
  'input',
  'highlight',
  'contentWidth',
  'emit',
  'nextTick',
  handlerCode
)(
  draft,
  input,
  overlay,
  width,
  (event, value) => events.push([event, value]),
  callback => Promise.resolve(callback())
)
handlers.start()
handlers.changed({ target: { value: '[取值时' } })
assert.equal(events.length, 0)
assert.equal(draft.value, '[取值时')
handlers.compositionEnd({ target: { value: '[取值时间]' } })
assert.deepEqual(events, [['update:modelValue', '[取值时间]']])
assert.equal(width.value, 400)
assert.equal(overlay.value.scrollTop, 108)
handlers.changed({ target: { value: '[取值时间]\n[未知字段]' } })
assert.equal(events[1][1], '[取值时间]\n[未知字段]')
assert.ok(descriptor.template.content.includes(':model-value="displayExpression(field)"'))
assert.ok(
  descriptor.template.content.includes('@update:model-value="updateExpression(field, $event)"')
)
console.log(
  'Dashboard formula names: saved-ID display/edit round-trip, dataset scoping, rename stability, literals, unknown references and Vue compilation passed'
)
