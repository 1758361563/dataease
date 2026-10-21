const fs = require('node:fs')
const path = require('node:path')
const Module = require('node:module')
const assert = require('node:assert/strict')
const ts = require('typescript')
const root = path.resolve(__dirname, '../src/views/chart/components')
const file = path.join(root, 'js/panel/charts/table/pivotAppearance.ts')
const mod = new Module(file)
mod.paths = module.paths
mod._compile(
  ts.transpileModule(fs.readFileSync(file, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText,
  file
)
const { migratePivotAppearance, applyPivotAppearance, pivotTotalBackground, pivotColorKey } =
  mod.exports
const legacy = () => ({
  type: 'table-pivot',
  customAttr: {
    basicStyle: { tableBorderColor: '#CCCCCC', tableColumnMode: 'auto' },
    tableHeader: {
      tableHeaderBgColor: '#F5F6F8',
      tableHeaderFontColor: '#1F2329',
      tableTitleFontSize: 13,
      tableTitleHeight: 36,
      isBolder: true,
      tableHeaderAlign: 'center',
      showVerticalBorder: true
    },
    tableCell: {
      tableItemFontSize: 13,
      tableItemHeight: 34,
      tableFontColor: '#1F2329',
      tableItemSubBgColor: '#FAFBFC',
      isBolder: false,
      tableItemAlign: 'center',
      showVerticalBorder: true
    },
    tableTotal: { row: { showGrandTotals: true }, col: { showGrandTotals: false } }
  }
})
for (const serialized of [false, true]) {
  const chart = legacy()
  const totals = structuredClone(chart.customAttr.tableTotal)
  if (serialized) chart.customAttr = JSON.stringify(chart.customAttr)
  migratePivotAppearance(chart)
  const attr = serialized ? JSON.parse(chart.customAttr) : chart.customAttr
  assert.equal(attr.tableHeader.tableTitleFontSize, 14)
  assert.equal(attr.tableHeader.tableTitleColFontSize, 14)
  assert.equal(attr.tableHeader.tableHeaderColBgColor, '#FAFBFD')
  assert.equal(attr.tableHeader.tableHeaderCornerAlign, 'center')
  assert.equal(attr.tableHeader.isColBolder, false)
  assert.equal(attr.tableCell.tableItemHeight, 38)
  assert.equal(attr.tableCell.tableItemAlign, 'center')
  assert.equal(attr.tableCell.tableItemSubBgColor, '#FBFCFE')
  assert.equal(attr.tableHeader.tableHeaderFontColor, '#65728A')
  assert.deepEqual(attr.tableTotal, totals)
  // After upgrade, even a deliberate edit back to an old default must survive.
  attr.tableCell.tableItemFontSize = 13
  attr.tableHeader.showVerticalBorder = true
  chart.customAttr = serialized ? JSON.stringify(attr) : attr
  const before = JSON.stringify(chart)
  migratePivotAppearance(chart)
  assert.equal(JSON.stringify(chart), before)
}
const custom = legacy()
Object.assign(custom.customAttr.tableHeader, {
  tableHeaderBgColor: '#123456',
  tableTitleFontSize: 18,
  tableHeaderAlign: 'left',
  isItalic: true
})
Object.assign(custom.customAttr.tableCell, {
  tableItemFontSize: 16,
  tableItemHeight: 52,
  tableFontColor: '#456789',
  tableItemAlign: 'right'
})
migratePivotAppearance(custom)
assert.equal(custom.customAttr.tableHeader.tableHeaderColBgColor, '#123456')
assert.equal(custom.customAttr.tableHeader.tableTitleCornerFontSize, 18)
assert.equal(custom.customAttr.tableHeader.tableHeaderCornerAlign, 'left')
assert.equal(custom.customAttr.tableHeader.isCornerItalic, true)
assert.equal(custom.customAttr.tableCell.tableItemFontSize, 16)
assert.equal(custom.customAttr.tableCell.tableItemHeight, 52)
assert.equal(custom.customAttr.tableCell.tableFontColor, '#456789')
assert.equal(custom.customAttr.tableCell.tableItemAlign, 'right')
const detail = { ...legacy(), type: 'table-info' }
const unchanged = JSON.stringify(detail)
migratePivotAppearance(detail)
assert.equal(JSON.stringify(detail), unchanged)
const theme = () =>
  Object.fromEntries(
    ['cornerCell', 'colCell', 'rowCell', 'dataCell'].map(kind => [
      kind,
      {
        cell: { verticalBorderWidth: 0, horizontalBorderWidth: 0 },
        text: { fontSize: 7.4, textAlign: 'right', fontFamily: 'Custom Font' },
        bolderText: { fontSize: 7.4 }
      }
    ])
  )
const chart = legacy()
migratePivotAppearance(chart)
const styled = applyPivotAppearance(theme(), chart)
assert.equal(styled.dataCell.text.fontWeight, 400)
assert.equal(styled.rowCell.text.fontWeight, 400)
assert.equal(styled.colCell.text.fontWeight, 600)
assert.equal(styled.dataCell.bolderText.fontWeight, 600)
assert.equal(styled.rowCell.text.textBaseline, 'middle')
for (const cell of Object.values(styled)) {
  assert.equal(cell.text.fontSize, 7.4, 'scaled font size must not be scaled again')
  assert.equal(cell.text.textAlign, 'right')
  assert.equal(cell.cell.verticalBorderWidth, 0)
  assert.equal(cell.cell.horizontalBorderWidth, 0)
}
chart.fontFamily = 'Custom Font'
assert.equal(applyPivotAppearance(theme(), chart).dataCell.text.fontFamily, 'Custom Font')
chart.customAttr.tableCell.isBolder = true
assert.equal(applyPivotAppearance(theme(), chart).dataCell.text.fontWeight, 600)
// Ensure the editor and renderer migrate the same settings before width/font scaling.
const renderer = fs.readFileSync(path.join(root, 'views/components/ChartComponentS2.vue'), 'utf8')
assert.match(renderer, /const handleDefaultVal[^]*?migratePivotAppearance\(chart\)/)
assert.ok(
  renderer.indexOf('handleDefaultVal(viewInfo)') <
    renderer.indexOf('recursionTransObj(customAttrTrans')
)
const editor = fs.readFileSync(path.join(root, 'editor/index.vue'), 'utf8')
assert.match(editor, /migratePivotAppearance\(view.value\)/)
const pivot = fs.readFileSync(path.join(root, 'js/panel/charts/table/table-pivot.ts'), 'utf8')
assert.match(pivot, /return applyPivotAppearance\(theme, chart\)/)
const v1 = legacy()
v1.customAttr.basicStyle.pivotAppearanceVersion = 1
v1.customAttr.tableCell.tableItemFontSize = 19
v1.customAttr.tableHeader.tableTitleHeight = 48
migratePivotAppearance(v1)
assert.equal(v1.customAttr.basicStyle.pivotAppearanceVersion, 4)
assert.equal(v1.customAttr.tableCell.tableItemFontSize, 19)
assert.equal(v1.customAttr.tableHeader.tableTitleHeight, 48)
// Real persisted editor format: these opaque rgba values were incorrectly
// treated as custom colours by versions 1/2, leaving the old grey backgrounds.
for (const version of [undefined, 1, 2]) {
  const rgba = legacy()
  rgba.customAttr.basicStyle.pivotAppearanceVersion = version
  Object.assign(rgba.customAttr.tableHeader, {
    tableHeaderBgColor: 'rgba(245, 246, 248, 1)',
    tableHeaderColBgColor: 'rgba(245, 246, 248, 1)',
    tableHeaderCornerBgColor: 'rgba(245, 246, 248, 1)'
  })
  Object.assign(rgba.customAttr.tableCell, {
    tableItemBgColor: 'rgba(255, 255, 255, 1)',
    tableItemSubBgColor: 'rgba(250, 251, 252, 1)'
  })
  rgba.customAttr = JSON.stringify(rgba.customAttr)
  migratePivotAppearance(rgba)
  const attr = JSON.parse(rgba.customAttr)
  assert.equal(attr.tableHeader.tableHeaderBgColor, '#F5F7FA')
  assert.equal(attr.tableHeader.tableHeaderColBgColor, '#FAFBFD')
  assert.equal(attr.tableHeader.tableHeaderCornerBgColor, '#F5F7FA')
  assert.equal(pivotColorKey(attr.tableCell.tableItemSubBgColor), '#FBFCFE')
  if (version) assert.equal(attr.tableCell.tableItemFontSize, 13)
  attr.tableHeader.tableHeaderColBgColor = 'rgba(245, 246, 248, 1)'
  rgba.customAttr = JSON.stringify(attr)
  migratePivotAppearance(rgba)
  assert.equal(
    JSON.parse(rgba.customAttr).tableHeader.tableHeaderColBgColor,
    'rgba(245, 246, 248, 1)',
    'later explicit changes must survive'
  )
}
assert.equal(pivotColorKey('#f5f6f8ff'), pivotColorKey('rgb(245, 246, 248)'))
assert.notEqual(pivotColorKey('rgba(245,246,248,0.5)'), pivotColorKey('#F5F6F8'))
const translucent = legacy()
translucent.customAttr.tableHeader.tableHeaderBgColor = 'rgba(245,246,248,0.5)'
migratePivotAppearance(translucent)
assert.equal(translucent.customAttr.tableHeader.tableHeaderBgColor, 'rgba(245,246,248,0.5)')
const white = { backgroundColor: '#FFFFFF', backgroundColorOpacity: 0.7 }
assert.equal(pivotTotalBackground(white, { isTotals: true }).backgroundColor, '#EDF3FB')
assert.equal(pivotTotalBackground(white, { isTotals: true }).backgroundColorOpacity, 0.7)
assert.equal(pivotTotalBackground(white, { isTotals: false }), white)
assert.equal(pivotTotalBackground(white, { isTotals: true, isMergedCell: true }), white)
assert.equal(pivotTotalBackground(white, { isTotals: true }, true), white)
assert.equal(
  pivotTotalBackground({ backgroundColor: 'rgba(250, 251, 253, 0.5)' }, { isTotals: true })
    .backgroundColor,
  'rgba(237,243,251,0.5)'
)
const customBackground = { backgroundColor: '#123456' }
assert.equal(pivotTotalBackground(customBackground, { isTotals: true }), customBackground)
// Exercise the actual custom cell overrides without creating a DOM/canvas.
const ast = ts.createSourceFile('pivot.ts', pivot, ts.ScriptTarget.Latest, true)
const cells = ast.statements.filter(
  s =>
    ts.isClassDeclaration(s) &&
    ['PivotAppearanceDataCell', 'PivotAppearanceRowCell'].includes(s.name.text)
)
class BaseCell {
  getBackgroundColor() {
    return this.originalBackground
  }
}
for (const source of cells) {
  const js = ts.transpileModule(source.getText(ast), {
    compilerOptions: { target: ts.ScriptTarget.ES2020 }
  }).outputText
  const Cell = new Function(
    'CustomDataCell',
    'RowCell',
    'pivotTotalBackground',
    `${js};return ${source.name.text}`
  )(BaseCell, BaseCell, pivotTotalBackground)
  const context = {
    originalBackground: white,
    meta: { isTotals: true },
    spreadsheet: { options: {} }
  }
  assert.equal(Cell.prototype.getBackgroundColor.call(context).backgroundColor, '#EDF3FB')
  context.spreadsheet.options.conditions = {
    background: [{ mapping: () => ({ fill: '#123456' }) }]
  }
  assert.equal(Cell.prototype.getBackgroundColor.call(context), white)
}
// Trace the migrated persisted values through the real shared and pivot theme
// builders, rather than checking the migration helper alone.
const { merge, isNumber } = require('lodash-es')
const readAst = relative => {
  const source = fs.readFileSync(path.join(root, relative), 'utf8')
  return ts.createSourceFile(relative, source, ts.ScriptTarget.Latest, true)
}
const utilAst = readAst('js/util.ts')
const commonAst = readAst('js/panel/common/common_table.ts')
const functions = ['hexColorToRGBA', 'isAlphaColor', 'resetRgbOpacity', 'parseJson']
const helpers = utilAst.statements
  .filter(s => ts.isFunctionDeclaration(s) && functions.includes(s.name.text))
  .map(s => s.getText(utilAst).replace(/^export /, ''))
  .join('\n')
const shared = commonAst.statements.find(
  s => ts.isFunctionDeclaration(s) && s.name.text === 'getCustomTheme'
)
const pivotClass = ast.statements.find(
  s => ts.isClassDeclaration(s) && s.name.text === 'TablePivot'
)
const configTheme = pivotClass.members.find(s => s.name?.getText(ast) === 'configTheme')
const themeSource = `${helpers}\n${shared.getText(commonAst).replace(/^export /, '')}
class Base {configTheme(chart){return getCustomTheme(chart)}}
class Pivot extends Base {${configTheme.getText(ast)}}
return new Pivot();`
const js = ts.transpileModule(themeSource, {
  compilerOptions: { target: ts.ScriptTarget.ES2020 }
}).outputText
const sample = legacy()
Object.assign(sample.customAttr.basicStyle, { alpha: 100, tableScrollBarColor: 'rgba(0,0,0,0.15)' })
Object.assign(sample.customAttr.tableHeader, {
  tableHeaderBgColor: 'rgba(245,246,248,1)',
  tableHeaderColBgColor: 'rgba(245,246,248,1)',
  tableHeaderCornerBgColor: 'rgba(245,246,248,1)'
})
Object.assign(sample.customAttr.tableCell, {
  tableItemBgColor: 'rgba(255,255,255,1)',
  tableItemSubBgColor: 'rgba(250,251,252,1)',
  enableTableCrossBG: true
})
migratePivotAppearance(sample)
const attr = sample.customAttr
const builder = new Function(
  'merge',
  'isNumber',
  'FONT_FAMILY',
  'DEFAULT_BASIC_STYLE',
  'DEFAULT_TABLE_HEADER',
  'DEFAULT_TABLE_CELL',
  'applyPivotAppearance',
  js
)(
  merge,
  isNumber,
  'sans-serif',
  attr.basicStyle,
  attr.tableHeader,
  attr.tableCell,
  applyPivotAppearance
)
sample.customAttr = JSON.stringify(attr)
const actualTheme = builder.configTheme(sample)
assert.equal(pivotColorKey(actualTheme.rowCell.cell.backgroundColor), '#FAFBFD')
assert.equal(pivotColorKey(actualTheme.colCell.cell.backgroundColor), '#F5F7FA')
assert.equal(pivotColorKey(actualTheme.dataCell.cell.backgroundColor), '#FFFFFF')
assert.equal(pivotColorKey(actualTheme.dataCell.cell.crossBackgroundColor), '#FBFCFE')
const v3 = legacy()
v3.customAttr.basicStyle.pivotAppearanceVersion = 3
v3.customAttr.tableHeader.tableHeaderBgColor = '#F5F7FA'
v3.customAttr.tableCell.tableItemSubBgColor = 'rgba(255,255,255,1)'
v3.customAttr.tableCell.tableItemFontSize = 17
migratePivotAppearance(v3)
assert.equal(v3.customAttr.tableCell.tableItemSubBgColor, '#FBFCFE')
assert.equal(v3.customAttr.tableHeader.tableHeaderBgColor, '#F5F7FA')
assert.equal(v3.customAttr.tableCell.tableItemFontSize, 17)
const noStripes = legacy()
noStripes.customAttr.tableCell.enableTableCrossBG = false
noStripes.customAttr.tableCell.tableItemSubBgColor = '#112233'
migratePivotAppearance(noStripes)
assert.equal(noStripes.customAttr.tableCell.enableTableCrossBG, false)
assert.equal(noStripes.customAttr.tableCell.tableItemSubBgColor, '#112233')
for (const color of ['#FBFCFE', 'rgba(251,252,254,1)'])
  assert.equal(
    pivotColorKey(
      pivotTotalBackground({ backgroundColor: color }, { isTotals: true }).backgroundColor
    ),
    '#EDF3FB'
  )
console.log('pivotAppearance: migration, custom settings, typography and render integration passed')
