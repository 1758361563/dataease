const fs = require('node:fs'),
  path = require('node:path'),
  vm = require('node:vm')
const test = require('node:test'),
  assert = require('node:assert/strict'),
  ts = require('typescript')
const { parse, compileScript, compileTemplate, compileStyleAsync } = require('@vue/compiler-sfc')
const root = path.resolve(__dirname, '../src/views/chart/components/editor/filter')
const read = file => parse(fs.readFileSync(path.join(root, file), 'utf8')).descriptor
const ref = value => ({ value }),
  plain = value => JSON.parse(JSON.stringify(value))
function functions(file, names, context) {
  const ast = ts.createSourceFile(
    'fixture.ts',
    read(file).scriptSetup.content,
    ts.ScriptTarget.Latest,
    true
  )
  const code = names
    .map(name => {
      const node = ast.statements
        .filter(ts.isVariableStatement)
        .flatMap(node => [...node.declarationList.declarations])
        .find(item => item.name.getText(ast) === name)
      assert.ok(node, name)
      return 'const ' + node.getText(ast)
    })
    .join('\n')
  vm.createContext(context)
  vm.runInContext(
    ts.transpileModule(code + '\nglobalThis.api = {' + names.join(',') + '}', {
      compilerOptions: { target: ts.ScriptTarget.ES2020 }
    }).outputText,
    context
  )
  return context.api
}
const condition = id => ({
  fieldId: id,
  name: id,
  field: { id, name: id, deType: 0 },
  filterType: 'enum',
  term: 'in',
  enumValue: ['已选'],
  value: ''
})

test('empty filters default to AND; existing OR and nested predicates round-trip', () => {
  let saved
  const state = {
    logic: ref('and'),
    relationList: ref([]),
    errorMessage: ref(''),
    t: value => value,
    emits: (_, value) => (saved = plain(value))
  }
  const api = functions(
    'auth-tree-chart/RowAuth.vue',
    ['init', 'dfsInit', 'dfsSubmit', 'errorDetected', 'getTimeValue', 'submit', 'addCondReal'],
    state
  )
  api.init({ logic: 'or', items: [] })
  assert.equal(state.logic.value, 'and')
  api.addCondReal('relation')
  assert.equal(state.relationList.value[0].logic, 'and')
  api.init({
    logic: 'or',
    items: [condition('A'), { subTree: { logic: 'or', items: [condition('B')] } }]
  })
  api.submit()
  assert.equal(saved.logic, 'or')
  assert.equal(saved.items[1].subTree.logic, 'or')
  assert.equal(saved.items[0].fieldId, 'A')
  assert.deepEqual(saved.items[0].enumValue, ['已选'])
  assert.equal(saved.errorMessage, '')
})

test('relation changes affect only the selected group; new groups use AND', () => {
  const events = []
  const api = functions('auth-tree-chart/AuthTree.vue', ['handleCommand', 'addCondReal', 'add'], {
    props: { logic: 'or' },
    emits: (...args) => events.push(args)
  })
  api.handleCommand('and')
  assert.deepEqual(events, [['update:logic', 'and']])
  api.addCondReal('relation')
  assert.deepEqual(events.at(-1), ['addCondReal', 'relation', 'and'])
})

test('editing and cancelling a filter draft does not change the saved chart', () => {
  let draft
  const state = {
    dialogVisible: ref(false),
    nextTick: callback => callback(),
    cloneDeep: structuredClone,
    computedFiledList: ref({ A: { id: 'A', name: '字段A', deType: 0 } }),
    rowAuth: ref({ init: value => (draft = value) })
  }
  const api = functions('FilterTree.vue', ['init', 'dfsTree', 'closeFilter'], state)
  const original = { logic: 'or', items: [condition('A')] },
    before = JSON.stringify(original)
  api.init(original)
  draft.items[0].enumValue.push('草稿值')
  api.closeFilter()
  assert.equal(JSON.stringify(original), before)
  assert.equal(state.dialogVisible.value, false)
})

test('enum requests ignore stale responses; zero/false, manual values and failed-request retry work', async () => {
  const pending = []
  const state = {
    item: ref({ enumValue: [] }),
    enumList: ref([]),
    enumQuery: ref(''),
    enumOptions: ref([0, false, '北京']),
    enumLoading: ref(false),
    enumLoadError: ref(false),
    enumRequest: 0,
    enumRequestField: '',
    authTargetType: ref(''),
    textareaValue: ref('自定义\n自定义\n另一个'),
    showTextArea: ref(true),
    computedFiledList: ref([
      { id: 'A', name: '字段A', deType: 0 },
      { id: 'B', name: '字段B', deType: 0 }
    ]),
    termNeedsValue: {
      get value() {
        return !['null', 'not_null', 'empty', 'not_empty'].includes(state.item.value.term)
      }
    },
    multFieldValuesForPermissions: args =>
      new Promise((resolve, reject) => pending.push({ args, resolve, reject }))
  }
  const api = functions(
    'auth-tree-chart/FilterFiled.vue',
    [
      'optionData',
      'initEnumOptions',
      'selectItem',
      'selectField',
      'openEnumPicker',
      'selectAllFiltered',
      'enumSelectionChange',
      'operatorChange',
      'addFields'
    ],
    state
  )
  const tick = () => new Promise(resolve => setImmediate(resolve))
  api.selectField('A')
  api.openEnumPicker(true)
  assert.equal(pending.length, 1)
  api.selectField('B')
  pending[1].resolve({ data: [0, false, '', null, '北京'] })
  await tick()
  pending[0].resolve({ data: ['旧字段A的值'] })
  await tick()
  assert.deepEqual(plain(state.enumList.value), [0, false, '', '北京'])
  api.addFields()
  assert.deepEqual(plain(state.item.value.enumValue), ['自定义', '另一个'])
  api.selectAllFiltered()
  assert.deepEqual(plain(state.item.value.enumValue), ['自定义', '另一个', 0, false, '北京'])
  state.item.value.term = 'eq'
  api.operatorChange('eq')
  assert.equal(state.item.value.enumValue.length, 1)
  state.item.value.term = 'not_null'
  api.operatorChange('not_null')
  assert.deepEqual(plain(state.item.value.enumValue), [])
  api.selectField('A')
  pending[2].reject(new Error('mock network failure'))
  await tick()
  assert.equal(state.enumLoadError.value, true)
  api.openEnumPicker(true)
  assert.equal(pending.length, 4)
  pending[3].resolve({ data: ['重试成功'] })
  await tick()
  assert.deepEqual(plain(state.enumList.value), ['重试成功'])
})

test('filter scripts, templates and Less styles compile', async () => {
  for (const file of [
    'FilterTree.vue',
    'auth-tree-chart/FilterFiled.vue',
    'auth-tree-chart/AuthTree.vue',
    'auth-tree-chart/RowAuth.vue'
  ]) {
    const descriptor = read(file),
      script = compileScript(descriptor, { id: file })
    assert.deepEqual(
      compileTemplate({
        source: descriptor.template.content,
        filename: file,
        id: file,
        compilerOptions: { bindingMetadata: script.bindings }
      }).errors,
      []
    )
    for (const style of descriptor.styles)
      assert.deepEqual(
        (
          await compileStyleAsync({
            source: style.content,
            filename: file,
            id: 'data-v-filter',
            scoped: style.scoped,
            preprocessLang: 'less'
          })
        ).errors,
        []
      )
  }
})
