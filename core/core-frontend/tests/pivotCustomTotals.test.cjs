const fs = require('node:fs')
const path = require('node:path')
const Module = require('node:module')
const test = require('node:test')
const assert = require('node:assert/strict')
const ts = require('typescript')
const { parse, compileScript, compileTemplate } = require('@vue/compiler-sfc')
const { get, minBy, maxBy } = require('lodash')
const root = path.resolve(__dirname, '../src')
const tablePath = 'views/chart/components/js/panel/charts/table/'
const editorPath = 'views/chart/components/editor/editor-style/components/table/'
const transpile = source =>
  ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText
function load(file) {
  const mod = new Module(file)
  mod.paths = module.paths
  const fallback = mod.require.bind(mod)
  mod.require = name => {
    if (name.startsWith('.')) {
      const target = path.resolve(path.dirname(file), name + '.ts')
      if (fs.existsSync(target)) return load(target)
    }
    return fallback(name)
  }
  mod._compile(transpile(fs.readFileSync(file, 'utf8')), file)
  return mod.exports
}
const identity = load(path.join(root, 'views/chart/components/js/chartFieldInstance.ts'))
const totals = load(path.join(root, tablePath, 'pivotTotalIdentity.ts'))
const { translateTotalFieldReferences: translate } = load(
  path.join(root, editorPath, 'customTotalExpression.ts')
)
function declarations(source, names) {
  const ast = ts.createSourceFile('test.ts', source, ts.ScriptTarget.Latest, true)
  return names
    .map(name => {
      let result
      for (const node of ast.statements) {
        if (ts.isFunctionDeclaration(node) && node.name?.text === name) result = node.getText(ast)
        if (ts.isVariableStatement(node)) {
          const declaration = node.declarationList.declarations.find(
            item => item.name.getText(ast) === name
          )
          if (declaration) result = 'const ' + declaration.getText(ast)
        }
      }
      assert.ok(result, `Missing production declaration ${name}`)
      return result.replace(/^export\s+/, '')
    })
    .join('\n')
}
const pivotSource = fs.readFileSync(path.join(root, tablePath, 'table-pivot.ts'), 'utf8')
const functions =
  declarations(pivotSource, [
    'mapTotalCfg',
    'customCalcFunc',
    'getCustomCalcResult',
    'getGridCustomCalcResult',
    'getTreeCustomCalcResult',
    'getSubLevel',
    'getTreePath'
  ]) +
  '\n' +
  declarations(fs.readFileSync(path.join(root, tablePath, 'pivotMeasureRows.ts'), 'utf8'), [
    'readGridColTotal'
  ])
const render = new Function(
  'resultKey',
  'findPivotTotalConfig',
  'EXTRA_FIELD',
  'get',
  'minBy',
  'maxBy',
  'keys',
  transpile(functions) + '\nreturn { mapTotalCfg, customCalcFunc };'
)(identity.resultKey, totals.findPivotTotalConfig, '$$extra$$', get, minBy, maxBy, Object.keys)
const ratio = { dataeaseName: 'ratio', fieldInstanceId: 'ratio_card', name: '占比' }
const cfg = [
  { dataeaseName: 'ratio', aggregation: 'CUSTOM', originName: 'SUM([new])/SUM([orders])' }
]
const chart = { customAttr: { basicStyle: { tableLayoutMode: 'grid' } } }
const resolve = (
  query,
  status,
  axisMap,
  customCalc,
  fields = [ratio],
  configs = cfg,
  view = chart
) =>
  render.customCalcFunc(
    query,
    [{ [identity.resultKey(fields[0])]: 1 }],
    status,
    view,
    render.mapTotalCfg(configs, fields),
    axisMap,
    customCalc
  )

test('legacy total settings migrate without losing formulas, labels or other aggregation choices', () => {
  const input = structuredClone(cfg)
  const normalized = totals.normalizePivotTotalConfigs(input, [ratio])
  assert.equal(normalized[0].dataeaseName, 'fi_ratio_card')
  assert.equal(normalized[0].originName, cfg[0].originName)
  assert.deepEqual(input, cfg)
  const legacy = { dataeaseName: 'old', name: 'original', chartShowName: 'display' }
  const defaults = totals.normalizePivotTotalConfigs([], [legacy])
  assert.equal(defaults[0].dataeaseName, 'old')
  assert.equal(defaults[0].label, 'display')
  assert.equal(defaults[0].aggregation, 'SUM')
  assert.equal(
    totals.normalizePivotTotalConfigs([{ dataeaseName: 'old', aggregation: 'AVG' }], [legacy])[0]
      .aggregation,
    'AVG'
  )
  assert.deepEqual(totals.normalizePivotTotalConfigs(cfg, []), [])
})

test('repeated placements prefer exact settings over a legacy entry and remain independently editable', () => {
  const second = { ...ratio, fieldInstanceId: 'second' }
  const mixed = [
    ...cfg,
    { dataeaseName: 'fi_ratio_card', aggregation: 'SUM' },
    { dataeaseName: 'fi_second', aggregation: 'CUSTOM', originName: 'SUM([other])' }
  ]
  const normalized = totals.normalizePivotTotalConfigs(mixed, [ratio, second])
  assert.equal(normalized[0].aggregation, 'SUM')
  assert.equal(normalized[1].originName, 'SUM([other])')
  normalized[0].originName = 'changed'
  assert.equal(normalized[1].originName, 'SUM([other])')
  assert.equal(mixed[0].originName, cfg[0].originName)
  const mapped = render.mapTotalCfg(mixed, [ratio, second])
  assert.equal(mapped.fi_ratio_card.aggregation, 'SUM')
  assert.equal(mapped.fi_second.originName, 'SUM([other])')
})

test('actual custom-total renderer reads 4/9 by instance key for grid and tree totals', () => {
  const axis = { row: [{ dataeaseName: 'date' }], col: [{ dataeaseName: 'category' }] }
  for (const mode of ['grid', 'tree']) {
    const value = resolve(
      { category: 'incense', $$extra$$: 'fi_ratio_card' },
      { isRowTotal: true },
      axis,
      { rowTotal: { data: { incense: { fi_ratio_card: '0.44444444' } } } },
      [ratio],
      cfg,
      { customAttr: { basicStyle: { tableLayoutMode: mode } } }
    )
    assert.ok(Math.abs(value - 4 / 9) < 1e-8)
  }
  assert.equal(
    resolve({ $$extra$$: 'fi_ratio_card' }, { isRowTotal: true, isColTotal: true }, axis, {
      rowColTotal: { data: { fi_ratio_card: '0.44444444' } }
    }),
    0.44444444
  )
})

test('column totals, both subtotal directions and zero retain their own result paths', () => {
  const axis = {
    row: [{ dataeaseName: 'r' }, { dataeaseName: 'r2' }],
    col: [{ dataeaseName: 'c' }, { dataeaseName: 'c2' }]
  }
  const query = { r: 'R', c: 'C', $$extra$$: 'fi_ratio_card' }
  assert.equal(
    resolve(query, { isColTotal: true }, axis, {
      colTotal: { data: { R: { fi_ratio_card: '0' } } }
    }),
    0
  )
  assert.equal(
    resolve(query, { isRowSubTotal: true }, axis, {
      rowSubTotal: [{ data: { C: { R: { fi_ratio_card: '0.25' } } } }]
    }),
    0.25
  )
  assert.equal(
    resolve(query, { isColSubTotal: true }, axis, {
      colSubTotal: [{ data: { R: { C: { fi_ratio_card: '0.75' } } } }]
    }),
    0.75
  )
  const legacy = { dataeaseName: 'ratio' }
  assert.equal(
    resolve(
      { $$extra$$: 'ratio' },
      { isRowTotal: true },
      { row: [{ dataeaseName: 'r' }], col: [] },
      { rowTotal: { data: { ratio: '0.5' } } },
      [legacy]
    ),
    0.5
  )
})

test('valid formulas translate repeated references and preserve large string IDs', () => {
  const fields = [
    { id: '9007199254740993999', name: '新客' },
    { id: '2', name: '开单' }
  ]
  const expression = 'SUM([新客])/NULLIF(SUM([开单]),0)+0*SUM([新客])'
  const saved = translate(expression, fields, 'name', 'id')
  assert.equal(saved, 'SUM([9007199254740993999])/NULLIF(SUM([2]),0)+0*SUM([9007199254740993999])')
  assert.equal(translate(saved, fields, 'id', 'name'), expression)
  assert.throws(() => translate('SUM([不存在])', fields, 'name', 'id'), /不存在/)
  assert.equal(translate('SUM([gone])', fields, 'id', 'name'), 'SUM([gone])')
  assert.throws(() => translate('SUM([undefined])', fields, 'name', 'id'), /undefined/)
  assert.throws(
    () =>
      translate(
        '[重名]',
        [
          { id: '1', name: '重名' },
          { id: '2', name: '重名' }
        ],
        'name',
        'id'
      ),
    /重名/
  )
})

function setupDeclaration(file, name) {
  const { descriptor } = parse(fs.readFileSync(path.join(root, editorPath, file), 'utf8'))
  return declarations(descriptor.scriptSetup.content, [name])
}
test('editor rejects unresolved or empty formulas without overwriting the saved expression', () => {
  const fieldForm = { originName: 'previous-valid-expression' }
  const messages = []
  let text = 'SUM([missing])'
  const setFieldForm = new Function(
    'mirror',
    'fieldForm',
    'quotaDataList',
    'translateTotalFieldReferences',
    'ElMessage',
    transpile(setupDeclaration('CustomAggrEdit.vue', 'setFieldForm')) + '\nreturn setFieldForm;'
  )(
    { value: { state: { doc: { toString: () => text } } } },
    fieldForm,
    [{ id: '1', name: '原始字段' }],
    translate,
    { error: message => messages.push(message) }
  )
  assert.equal(setFieldForm(), false)
  assert.equal(fieldForm.originName, 'previous-valid-expression')
  text = '  '
  assert.equal(setFieldForm(), false)
  assert.equal(messages.length, 2)
  text = 'SUM([原始字段])'
  assert.equal(setFieldForm(), true)
  assert.equal(fieldForm.originName, 'SUM([1])')
})

test('selector keeps the formula dialog open and emits nothing when validation fails', () => {
  let changes = 0
  const confirm = new Function(
    'calcEdit',
    'state',
    'cloneDeep',
    'setFieldDefaultValue',
    'closeEditCalc',
    'changeTableTotal',
    transpile(setupDeclaration('TableTotalSelector.vue', 'confirmEditCalc')) +
      '\nreturn confirmEditCalc;'
  )(
    { value: { setFieldForm: () => false } },
    {},
    structuredClone,
    () => changes++,
    () => changes++,
    () => changes++
  )
  confirm()
  assert.equal(changes, 0)
  const setup = new Function(
    'normalizePivotTotalConfigs',
    transpile(setupDeclaration('TableTotalSelector.vue', 'setupTotalCfg')) +
      '\nreturn setupTotalCfg;'
  )(totals.normalizePivotTotalConfigs)
  const saved = structuredClone(cfg)
  setup(saved, [ratio])
  assert.equal(saved[0].dataeaseName, 'fi_ratio_card')
  assert.equal(saved[0].originName, cfg[0].originName)
})

test('both changed Vue scripts and templates compile', () => {
  for (const file of ['CustomAggrEdit.vue', 'TableTotalSelector.vue']) {
    const source = fs.readFileSync(path.join(root, editorPath, file), 'utf8')
    const parsed = parse(source)
    assert.deepEqual(parsed.errors, [])
    const script = compileScript(parsed.descriptor, { id: file })
    const template = compileTemplate({
      source: parsed.descriptor.template.content,
      filename: file,
      id: file,
      compilerOptions: { bindingMetadata: script.bindings }
    })
    assert.deepEqual(template.errors, [])
  }
})
