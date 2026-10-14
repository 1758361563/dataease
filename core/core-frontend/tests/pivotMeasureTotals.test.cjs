const fs = require('node:fs')
const path = require('node:path')
const Module = require('node:module')
const assert = require('node:assert/strict')
const ts = require('typescript')
const Exceljs = require('exceljs')
global.window = { devicePixelRatio: 1 }
const transpile = source =>
  ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText
function load(relative) {
  const file = path.resolve(__dirname, relative)
  const module = new Module(file)
  module.paths = require.main.paths
  const originalRequire = module.require.bind(module)
  module.require = name =>
    name.endsWith('chartFieldInstance')
      ? load('../src/views/chart/components/js/chartFieldInstance.ts')
      : originalRequire(name)
  module._compile(transpile(fs.readFileSync(file, 'utf8')), file)
  return module.exports
}
const { expandMeasureFirstTotals } = load(
  '../src/views/chart/components/js/panel/charts/table/pivotMeasureTotals.ts'
)
const { fitPivotTotalColumns } = load(
  '../src/views/chart/components/js/panel/charts/table/pivotTotalWidth.ts'
)
const { configurePivotDisplayScope, fitSummaryOnlyHeader } = load(
  '../src/views/chart/components/js/panel/charts/table/pivotDisplayScope.ts'
)
const { pivotHeaderFields } = load(
  '../src/views/chart/components/js/panel/charts/table/pivotHeaderLayout.ts'
)
const { isScopedPercent, pivotPercentValue } = load(
  '../src/views/chart/components/js/panel/charts/table/pivotPercent.ts'
)
// Expose the installed engine's real hierarchy builder instead of synthesizing total queries.
const engineFile = path.resolve(__dirname, '../node_modules/@antv/s2/esm/index.js')
const engine = new Module(engineFile)
engine.paths = module.paths
engine._compile(
  transpile(
    fs.readFileSync(engineFile, 'utf8') +
      '\nexport {handleGridRowColHierarchy as buildDiagnosticHierarchy, PivotFacet as DiagnosticFacet};'
  ),
  engineFile
)
const {
  PivotDataSet,
  Node,
  Hierarchy,
  EXTRA_FIELD,
  VALUE_FIELD,
  Aggregation,
  QueryDataType,
  buildDiagnosticHierarchy,
  DiagnosticFacet
} = engine.exports
const source = ts.createSourceFile(
  'pivot.ts',
  fs.readFileSync(
    path.resolve(__dirname, '../src/views/chart/components/js/panel/charts/table/table-pivot.ts'),
    'utf8'
  ),
  ts.ScriptTarget.Latest,
  true
)
let datasetClass, totalResolver
function visit(node) {
  if (ts.isClassDeclaration(node) && node.name?.text === 'CustomPivotDataset')
    datasetClass = node.getText(source)
  if (ts.isFunctionDeclaration(node) && node.name?.text === 'getAggregationAndCalcFuncByQuery')
    totalResolver = node.getText(source)
  ts.forEachChild(node, visit)
}
visit(source)
const Dataset = new Function(
  'PivotDataSet',
  'Aggregation',
  'QueryDataType',
  'calcActionByType',
  'some',
  'isEmpty',
  'EXTRA_FIELD',
  'VALUE_FIELD',
  'resultKey',
  'isScopedPercent',
  'pivotPercentValue',
  'decodeGlobalPivotValue',
  transpile(
    totalResolver + '\n' + datasetClass.replace('class CustomPivotDataset', 'class Dataset')
  ) + '\nreturn Dataset'
)(
  PivotDataSet,
  Aggregation,
  QueryDataType,
  {
    [Aggregation.SUM]: (data, field) => data.reduce((sum, row) => sum + Number(row[field] || 0), 0)
  },
  value => Object.values(value || {}).some(Boolean),
  value => !value || !Object.keys(value).length,
  EXTRA_FIELD,
  VALUE_FIELD,
  field => field.dataeaseName,
  isScopedPercent,
  pivotPercentValue,
  value => value
)
const commonSource = ts.createSourceFile(
  'common.ts',
  fs.readFileSync(
    path.resolve(__dirname, '../src/views/chart/components/js/panel/common/common_table.ts'),
    'utf8'
  ),
  ts.ScriptTarget.Latest,
  true
)
const exportFunctions = commonSource.statements
  .filter(
    node =>
      ts.isFunctionDeclaration(node) &&
      ['exportGridPivot', 'extractNumber'].includes(node.name?.text)
  )
  .map(node => node.getText(commonSource))
  .join('\n')
const exportJobs = []
async function checkExcel(sheet, layout, chartFields) {
  let blob
  const exportModule = { exports: {} }
  const exporter = new Function(
    'Exceljs',
    'i18nt',
    'ElMessage',
    'saveAs',
    'resultKey',
    'module',
    'exports',
    transpile(exportFunctions) + '\nreturn exportGridPivot'
  )(
    Exceljs,
    value => value,
    {
      warning: message => {
        throw new Error(message)
      }
    },
    value => {
      blob = value
    },
    field => field.dataeaseName,
    exportModule,
    exportModule.exports
  )
  const axis = chartFields.map(field => ({
    ...field,
    formatterCfg:
      field.dataeaseName === 'share'
        ? { type: 'percent', decimalCount: 1, thousandSeparator: false }
        : { type: 'value', decimalCount: 0, thousandSeparator: false, suffix: '' }
  }))
  const instance = {
    ...sheet,
    facet: { layoutResult: layout },
    dataCfg: { ...sheet.dataCfg, meta: sheet.dataSet.meta }
  }
  await exporter(instance, { yAxis: axis, title: 'summary-scope' })
  const workbook = new Exceljs.Workbook()
  await workbook.xlsx.load(await blob.arrayBuffer())
  const worksheet = workbook.worksheets[0]
  assert.equal(
    worksheet.columnCount,
    5,
    'Excel must contain row label, both total metrics and only the two share detail columns'
  )
  const index = layout.rowLeafNodes.findIndex(node => node.query.category === 'A')
  const row = layout.colsHierarchy.maxLevel + index + 2
  assert.equal(worksheet.getCell(row, 2).value, 100)
  assert.ok(Math.abs(worksheet.getCell(row, 3).value - 0.333) < 1e-12)
  assert.equal(worksheet.getCell(row, 3).numFmt, '0.0%')
}
for (const scope of ['none', 'sales', 'all'])
  for (const order of ['measure-first', 'dimension-first'])
    for (const mode of ['colAdapt', 'custom', 'adapt']) {
      const columns = ['day', 'weekday']
      const values = ['sales', 'share']
      const basis = [
        { category: 'A', day: '2026-10-12', weekday: '2026-10-12', sales: 80, share: 80 },
        { category: 'B', day: '2026-10-12', weekday: '2026-10-12', sales: 20, share: 20 },
        { category: 'A', day: '2026-10-13', weekday: '2026-10-13', sales: 20, share: 20 },
        { category: 'B', day: '2026-10-13', weekday: '2026-10-13', sales: 180, share: 180 }
      ]
      const fields = pivotHeaderFields(['category'], columns, values, { pivotHeaderOrder: order })
      const totalCfg = {
        showGrandTotals: true,
        reverseLayout: true,
        label: '总计',
        calcTotals: { aggregation: Aggregation.SUM }
      }
      const chartFields = values.map(dataeaseName => ({
        dataeaseName,
        pivotDisplayScope: scope === 'all' || scope === dataeaseName ? 'totals' : 'all'
      }))
      const configuredTotals = { col: totalCfg }
      const arrange = configurePivotDisplayScope(
        { yAxis: chartFields },
        { quotaPosition: 'col' },
        configuredTotals
      )
      const sheet = {
        dataCfg: { fields },
        store: new Map(),
        options: {
          totals: { col: totalCfg, row: totalCfg },
          style: { colCfg: { height: 30, width: mode === 'custom' ? 120 : () => 60 } }
        },
        isValueInCols: () => true,
        isHierarchyTreeType: () => false,
        isTableMode: () => false,
        isPivotMode: () => true,
        getTotalsConfig: () => totalCfg,
        theme: {
          dataCell: {
            cell: { padding: { left: 8, right: 8 } },
            text: { fontSize: 12 },
            bolderText: { fontSize: 14 }
          }
        },
        measureTextWidth: (text, font) => text.length * font.fontSize
      }
      if (arrange) sheet.options.layoutArrange = arrange
      const ds = new Dataset(sheet)
      ds.percentContext = {
        chart: {
          xAxis: [{ dataeaseName: 'category' }],
          xAxisExt: columns.map(dataeaseName => ({ dataeaseName })),
          yAxis: [
            { dataeaseName: 'sales' },
            { dataeaseName: 'share', compareCalc: { type: 'percent', percentScope: 'column' } }
          ]
        },
        basis
      }
      sheet.dataSet = ds
      ds.setDataCfg({
        fields,
        meta: [
          { field: 'sales', name: '业绩', formatter: value => String(value) },
          { field: 'share', name: '业绩占比', formatter: value => `${(value * 100).toFixed(1)}%` }
        ],
        data: basis.map(row => ({
          ...row,
          share: row.share / (row.day === '2026-10-12' ? 100 : 200)
        })),
        totalData: []
      })
      const h = new Hierarchy()
      const root = new Node({ id: 'root', level: -1, spreadsheet: sheet })
      buildDiagnosticHierarchy({
        isValueInCols: true,
        moreThanOneValue: true,
        rootNode: root,
        facetCfg: {
          spreadsheet: sheet,
          dataSet: ds,
          columns: ds.fields.columns,
          rows: ds.fields.rows,
          values,
          colCfg: {},
          layoutArrange: arrange
        },
        hierarchy: h,
        fields: ds.fields.columns,
        isRowHeader: false
      })
      h.getNodes().forEach(node => {
        node.height = node.isGrandTotals ? 90 : 30
        node.width = 60
        node.y = node.level * 30
      })
      h.sampleNodesForAllLevels.forEach(node => {
        node.height = 30
      })
      const leaves = h.getLeaves()
      const layout = {
        colLeafNodes: leaves,
        colNodes: h.getNodes(),
        colsHierarchy: h,
        rowLeafNodes: [
          { query: { category: 'A' } },
          { query: { category: 'B' } },
          { query: {}, isGrandTotals: true }
        ]
      }
      if (scope === 'all') {
        h.height = 0
        fitSummaryOnlyHeader(sheet, layout, true)
      }
      if (order === 'measure-first') {
        assert.equal(
          leaves.filter(node => node.isGrandTotals).length,
          1,
          'reproduce the engine bug'
        )
        assert.equal(leaves.find(node => node.isGrandTotals).query[EXTRA_FIELD], undefined)
      }
      expandMeasureFirstTotals(sheet, layout)
      const totalLeaves = leaves.filter(node => node.isGrandTotals)
      assert.equal(totalLeaves.length, 2)
      assert.deepEqual(
        totalLeaves.map(node => node.query[EXTRA_FIELD]),
        values
      )
      if (scope !== 'none') {
        const detailMetrics = new Set(
          leaves.filter(node => !node.isTotals).map(node => node.query[EXTRA_FIELD])
        )
        assert.deepEqual(
          [...detailMetrics],
          scope === 'sales' ? ['share'] : [],
          `${scope}/${order}/${mode}: ${JSON.stringify(
            leaves.map(node => ({
              metric: node.query[EXTRA_FIELD],
              total: node.isTotals,
              parent: node.parent?.id
            }))
          )}`
        )
        assert.equal(totalCfg.showGrandTotals, true)
        assert.equal(totalCfg.reverseLayout, true)
        const restored = JSON.parse(JSON.stringify({ yAxis: chartFields }))
        assert.equal(restored.yAxis[0].pivotDisplayScope, 'totals')
      }
      const actual = category =>
        totalLeaves.map(
          node =>
            ds.getCellData({
              query: { ...(category ? { category } : {}), ...node.query },
              isTotals: true,
              totalStatus: { isColTotal: true, ...(!category ? { isRowTotal: true } : {}) }
            })[VALUE_FIELD]
        )
      assert.deepEqual(
        actual('A'),
        [100, 1 / 3],
        'share totals must use the raw basis, not sum daily ratios'
      )
      assert.deepEqual(actual('B'), [200, 2 / 3])
      assert.deepEqual(actual(), [300, 1])
      assert.deepEqual(
        totalLeaves.map((node, index) =>
          ds.getFieldFormatter(node.query[EXTRA_FIELD])(actual('A')[index])
        ),
        ['100', '33.3%']
      )
      if (order === 'measure-first') {
        const parent = totalLeaves[0].parent
        assert.equal(parent, totalLeaves[1].parent)
        assert.equal(parent.label, '总计')
        assert.equal(parent.isLeaf, false)
        assert.equal(parent.height, 30)
        assert.ok(totalLeaves.every(node => node.height === 60 && node.y === 30))
        assert.deepEqual(
          totalLeaves.map(node => node.label),
          ['业绩', '业绩占比']
        )
        assert.equal(h.getIndexNodes().length, leaves.length)
        assert.ok(leaves.every((node, index) => node.rowIndex === index))
        if (mode === 'colAdapt') fitPivotTotalColumns(sheet, layout)
        assert.equal(
          parent.width,
          totalLeaves.reduce((sum, node) => sum + node.width, 0)
        )
        assert.equal(
          h.width,
          leaves.reduce((sum, node) => sum + node.width, 0)
        )
        const count = h.getNodes().length
        expandMeasureFirstTotals(sheet, layout)
        assert.equal(h.getNodes().length, count, 'repeat layout must not duplicate total nodes')
        assert.equal(sheet.facet, undefined, 'initial construction must not need sheet.facet')
      }
      // Date+weekday details remain separate and unchanged.
      const nativeLayout = DiagnosticFacet.prototype.doLayout.call({
        cfg: {
          spreadsheet: sheet,
          dataSet: ds,
          rows: ds.fields.rows,
          columns: ds.fields.columns,
          values,
          colCfg: {},
          layoutArrange: arrange
        },
        getMeasureInfo: () => ({}),
        calculateNodesCoordinate: (rows, rowHierarchy, cols, colHierarchy) => {
          colHierarchy.getNodes().forEach(node => {
            node.height = node.isGrandTotals ? 90 : 30
            node.width = 60
            node.y = node.level * 30
          })
          colHierarchy.sampleNodesForAllLevels.forEach(node => {
            node.height = 30
          })
          rows.forEach((node, index) => {
            node.height = 30
            node.y = index * 30
          })
          cols.forEach((node, index) => {
            node.colIndex = index
            node.rowIndex = index
          })
        }
      })
      if (scope === 'all') fitSummaryOnlyHeader(sheet, nativeLayout, true)
      expandMeasureFirstTotals(sheet, nativeLayout)
      if (mode === 'colAdapt') fitPivotTotalColumns(sheet, nativeLayout)
      const rowIndex = nativeLayout.rowLeafNodes.findIndex(node => node.query.category === 'A')
      const totalIndexes = nativeLayout.colLeafNodes
        .map((node, index) => (node.isGrandTotals ? index : -1))
        .filter(index => index >= 0)
      assert.deepEqual(
        totalIndexes.map(index => nativeLayout.getCellMeta(rowIndex, index).valueField),
        values
      )
      assert.equal(
        nativeLayout.colLeafNodes.length,
        scope === 'all' ? 2 : scope === 'sales' ? 4 : 6
      )
      if (scope === 'sales' && mode === 'colAdapt')
        exportJobs.push(checkExcel(sheet, nativeLayout, chartFields))
      assert.deepEqual(
        totalIndexes.map(index => nativeLayout.getCellMeta(rowIndex, index).fieldValue),
        [100, 1 / 3],
        'native cell-meta closure must see expanded leaves'
      )
      assert.equal(
        ds.getCellData({
          query: { category: 'A', day: '2026-10-12', weekday: '2026-10-12', [EXTRA_FIELD]: 'sales' }
        })[VALUE_FIELD],
        80
      )
    }
Promise.all(exportJobs)
  .then(() =>
    console.log(
      'Real S2 summary-only scopes, total headers, weighted percentages, width modes and actual Excel exports passed'
    )
  )
  .catch(error => {
    console.error(error)
    process.exitCode = 1
  })
