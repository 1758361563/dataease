const fs = require('node:fs')
const path = require('node:path')
const Module = require('node:module')
const test = require('node:test')
const assert = require('node:assert/strict')
const ts = require('typescript')
const { parse, compileScript, compileTemplate } = require('@vue/compiler-sfc')
const transpile = source =>
  ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText
function load(file) {
  const mod = new Module(file)
  mod.paths = module.paths
  const fallback = mod.require.bind(mod)
  mod.require = name =>
    name.startsWith('.') ? load(path.resolve(path.dirname(file), name + '.ts')) : fallback(name)
  mod._compile(transpile(fs.readFileSync(file, 'utf8')), file)
  return mod.exports
}
const root = path.resolve(__dirname, '../src/views/chart/components')
const { getPivotSubtotalOptions, normalizePivotSubtotalDimensions } = load(
  path.join(root, 'js/panel/charts/table/pivotSubtotals.ts')
)
const { ensurePivotDimensionInstances, resultKey, formatPivotDate } = load(
  path.join(root, 'js/chartFieldInstance.ts')
)
function makeChart(instances = false) {
  return {
    type: 'table-pivot',
    xAxis: ['y_M', 'y_W', 'y_W'].map((dateStyle, i) => ({
      id: 'date',
      dataeaseName: 'date',
      name: '取值时间',
      deType: 1,
      dateStyle,
      dateShowFormat: i === 2 ? 'week_range' : 'default',
      ...(instances ? { fieldInstanceId: `date_${i}` } : {})
    })),
    xAxisExt: [{ id: 'weekday', dataeaseName: 'weekday' }]
  }
}
const editorSource = fs.readFileSync(
  path.join(root, 'editor/editor-style/components/table/TableTotalSelector.vue'),
  'utf8'
)
const { descriptor } = parse(editorSource)
const ast = ts.createSourceFile(
  'selector.ts',
  descriptor.scriptSetup.content,
  ts.ScriptTarget.Latest,
  true
)
function declaration(name) {
  for (const node of ast.statements) {
    if (ts.isFunctionDeclaration(node) && node.name?.text === name) return node.getText(ast)
    if (ts.isVariableStatement(node)) {
      const item = node.declarationList.declarations.find(item => item.name.getText(ast) === name)
      if (item) return 'const ' + item.getText(ast)
    }
  }
  throw new Error(name)
}

test('repeated date placements have unique keys and readable subtotal choices without mutating chart', () => {
  for (const explicit of [false, true]) {
    const chart = makeChart(explicit)
    const before = JSON.stringify(chart)
    const options = getPivotSubtotalOptions(chart)
    assert.deepEqual(
      options.map(item => item.displayName),
      ['取值时间（月）', '取值时间（周）']
    )
    assert.equal(new Set(options.map(item => item.name)).size, 2)
    assert.equal(JSON.stringify(chart), before)
    ensurePivotDimensionInstances(chart)
    assert.deepEqual(
      options.map(item => item.name),
      chart.xAxis.slice(0, -1).map(resultKey)
    )
  }
})

test('legacy selections migrate; explicit selections, empty selection and defaults remain distinct', () => {
  const chart = makeChart()
  const keys = getPivotSubtotalOptions(chart).map(item => item.name)
  const row = { subTotalsDimensionsNew: true, subTotalsDimensions: ['date'] }
  assert.deepEqual(normalizePivotSubtotalDimensions(chart, row), keys)
  assert.deepEqual(row.subTotalsDimensions, ['date'])
  for (const selected of [[], [keys[0]], [keys[1]]]) {
    assert.deepEqual(
      normalizePivotSubtotalDimensions(chart, { ...row, subTotalsDimensions: selected }),
      selected
    )
  }
  assert.deepEqual(normalizePivotSubtotalDimensions(chart, {}), keys)
  assert.deepEqual(getPivotSubtotalOptions({ ...chart, xAxis: [chart.xAxis[0]] }), [])
  const mixed = makeChart(true)
  delete mixed.xAxis[0].fieldInstanceId
  for (const selected of [['date'], ['fi_date_1']]) {
    assert.deepEqual(
      normalizePivotSubtotalDimensions(mixed, { ...row, subTotalsDimensions: selected }),
      selected,
      'mixed legacy and instance placements must remain independently selectable'
    )
  }
})

test('actual selector can switch month/week and save independent checkboxes after reopening', () => {
  const chart = makeChart()
  const state = {
    tableTotalForm: { row: { subTotalsDimensionsNew: true, subTotalsDimensions: ['date'] } }
  }
  const emitted = []
  const api = new Function(
    'props',
    'state',
    'find',
    'getPivotSubtotalOptions',
    'normalizePivotSubtotalDimensions',
    'changeTableTotal',
    transpile(
      [
        'initSubTotalDimensionList',
        'onSelectedSubTotalDimensionNameChange',
        'changeRowSubTableTotal'
      ]
        .map(declaration)
        .join('\n')
    ) +
      '\nreturn { initSubTotalDimensionList, onSelectedSubTotalDimensionNameChange, changeRowSubTableTotal }'
  )(
    { chart },
    state,
    (items, predicate) => items.find(predicate),
    getPivotSubtotalOptions,
    normalizePivotSubtotalDimensions,
    () => emitted.push(JSON.parse(JSON.stringify(state.tableTotalForm)))
  )
  api.initSubTotalDimensionList()
  const [month, week] = state.subTotalDimensionList
  state.selectedSubTotalDimensionName = week.name
  api.onSelectedSubTotalDimensionNameChange(week.name)
  assert.equal(state.selectedSubTotalDimension, week)
  week.checked = false
  api.changeRowSubTableTotal()
  assert.deepEqual(emitted.at(-1).row.subTotalsDimensions, [month.name])
  api.initSubTotalDimensionList()
  assert.equal(state.selectedSubTotalDimension.name, week.name)
  assert.equal(state.selectedSubTotalDimension.checked, false)
  assert.equal(state.subTotalDimensionList[0].checked, true)
  const script = compileScript(descriptor, { id: 'subtotal-selector' })
  assert.deepEqual(
    compileTemplate({
      source: descriptor.template.content,
      filename: 'TableTotalSelector.vue',
      id: 'subtotal-selector',
      compilerOptions: { bindingMetadata: script.bindings }
    }).errors,
    []
  )
})

global.window = { devicePixelRatio: 1 }
const engineFile = path.resolve(__dirname, '../node_modules/@antv/s2/esm/index.js')
const engine = new Module(engineFile)
engine.paths = module.paths
engine._compile(
  transpile(
    fs.readFileSync(engineFile, 'utf8') +
      '\nexport { handleGridRowColHierarchy as buildHierarchy };'
  ),
  engineFile
)
const { PivotDataSet, Node, Hierarchy, SpreadSheet, buildHierarchy, EXTRA_FIELD, VALUE_FIELD } =
  engine.exports

test('real S2 hierarchy inserts monthly subtotal rows with weekday sums and grand total disabled', () => {
  const chart = makeChart()
  ensurePivotDimensionInstances(chart)
  const [month, week, range] = chart.xAxis.map(resultKey)
  const options = getPivotSubtotalOptions(chart)
  for (const selected of [['date'], [options[0].name], [options[1].name], []]) {
    const row = {
      showGrandTotals: false,
      showSubTotals: true,
      reverseSubLayout: false,
      subLabel: '小计',
      subTotalsDimensionsNew: true,
      subTotalsDimensions: selected,
      calcTotals: { aggregation: 'SUM' },
      calcSubTotals: { aggregation: 'SUM' }
    }
    row.subTotalsDimensions = normalizePivotSubtotalDimensions(chart, row)
    const fields = {
      rows: [month, week, range],
      columns: ['weekday'],
      values: ['sales'],
      valueInCols: true
    }
    const sheet = {
      dataCfg: { fields },
      store: new Map(),
      options: { totals: { row, col: { showGrandTotals: false, showSubTotals: false } } },
      isValueInCols: () => true,
      isHierarchyTreeType: () => false,
      isTableMode: () => false,
      isPivotMode: () => true,
      getTotalsConfig: SpreadSheet.prototype.getTotalsConfig
    }
    const ds = new PivotDataSet(sheet)
    sheet.dataSet = ds
    ds.setDataCfg({
      fields,
      totalData: [],
      data: [
        { [month]: '2026-10', [week]: 'W43', [range]: 'R43', weekday: '星期一', sales: 100 },
        { [month]: '2026-10', [week]: 'W44', [range]: 'R44', weekday: '星期一', sales: 200 },
        { [month]: '2026-10', [week]: 'W44', [range]: 'R44', weekday: '星期二', sales: 50 },
        { [month]: '2026-11', [week]: 'W44', [range]: 'R44', weekday: '星期一', sales: 10 },
        { [month]: '2026-11', [week]: 'W45', [range]: 'R45', weekday: '星期一', sales: 20 }
      ]
    })
    const hierarchy = new Hierarchy()
    buildHierarchy({
      isValueInCols: true,
      moreThanOneValue: false,
      rootNode: new Node({ id: 'root', level: -1, spreadsheet: sheet }),
      facetCfg: {
        spreadsheet: sheet,
        dataSet: ds,
        rows: ds.fields.rows,
        columns: ds.fields.columns,
        values: ['sales'],
        rowCfg: {},
        colCfg: {}
      },
      hierarchy,
      fields: ds.fields.rows,
      isRowHeader: true
    })
    const leaves = hierarchy.getLeaves()
    const subtotals = leaves.filter(node => node.isSubTotals)
    assert.equal(
      leaves.some(node => node.isGrandTotals),
      false
    )
    const monthly = subtotals.filter(node => node.query[week] == null)
    assert.equal(monthly.length, selected.includes('date') || selected.includes(month) ? 2 : 0)
    if (!selected.length) assert.equal(subtotals.length, 0)
    if (selected.includes(week)) assert.equal(subtotals.length, 4)
    for (const node of monthly) {
      assert.equal(node.label, '小计')
      const index = leaves.indexOf(node)
      assert.equal(
        leaves[index - 1].query[month],
        node.query[month],
        'subtotal follows its month details'
      )
      const cell = ds.getCellData({
        query: { ...node.query, weekday: '星期一', [EXTRA_FIELD]: 'sales' },
        isTotals: true,
        totalStatus: { isRowSubTotal: true }
      })
      assert.equal(cell[VALUE_FIELD], node.query[month] === '2026-10' ? 300 : 30)
    }
  }
})

test('two weeks share seven weekday columns and monthly subtotals sum matching weekdays', () => {
  const chart = makeChart(true)
  const [month, week, range] = chart.xAxis.map(resultKey)
  const fields = {
    rows: [month, week, range],
    columns: ['weekday'],
    values: ['sales'],
    valueInCols: true
  }
  const totals = {
    showGrandTotals: false,
    showSubTotals: false,
    calcTotals: { aggregation: 'SUM' },
    calcSubTotals: { aggregation: 'SUM' }
  }
  const sheet = {
    dataCfg: { fields },
    store: new Map(),
    options: { totals: { row: totals, col: totals } },
    isValueInCols: () => true,
    isHierarchyTreeType: () => false,
    isTableMode: () => false,
    isPivotMode: () => true,
    getTotalsConfig: SpreadSheet.prototype.getTotalsConfig
  }
  const ds = new PivotDataSet(sheet)
  sheet.dataSet = ds
  ds.setDataCfg({
    fields,
    totalData: [],
    data: [1, 2].flatMap(w =>
      [1, 2, 3, 4, 5, 6, 7].map(day => ({
        [month]: '2026-11',
        [week]: `W${w}`,
        [range]: `R${w}`,
        weekday: String(day),
        sales: w * 10 + day
      }))
    )
  })
  const hierarchy = new Hierarchy()
  buildHierarchy({
    isValueInCols: true,
    moreThanOneValue: false,
    rootNode: new Node({ id: 'root', level: -1, spreadsheet: sheet }),
    facetCfg: {
      spreadsheet: sheet,
      dataSet: ds,
      rows: ds.fields.rows,
      columns: ds.fields.columns,
      values: ['sales'],
      rowCfg: {},
      colCfg: {}
    },
    hierarchy,
    fields: ds.fields.columns,
    isRowHeader: false
  })
  const weekday = { deType: 1, dateStyle: 'y_M_d', dateShowFormat: 'weekday_name' }
  const leaves = hierarchy.getLeaves()
  assert.equal(leaves.length, 7, 'columns must not repeat for the second week')
  assert.deepEqual(
    leaves.map(node => formatPivotDate(node.query.weekday, weekday)),
    ['星期一', '星期二', '星期三', '星期四', '星期五', '星期六', '星期日']
  )
  for (let day = 1; day <= 7; day++) {
    const cell = ds.getCellData({
      query: { [month]: '2026-11', weekday: String(day), [EXTRA_FIELD]: 'sales' },
      isTotals: true,
      totalStatus: { isRowSubTotal: true }
    })
    assert.equal(cell[VALUE_FIELD], 30 + day * 2)
  }
})
