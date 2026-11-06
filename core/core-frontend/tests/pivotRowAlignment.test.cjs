const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const test = require('node:test')
const ts = require('typescript')
global.window = { devicePixelRatio: 1 }
const { RowCell } = require('@antv/s2')
const source = fs.readFileSync(
  path.resolve(__dirname, '../src/views/chart/components/js/panel/charts/table/table-pivot.ts'),
  'utf8'
)
const ast = ts.createSourceFile('pivot.ts', source, ts.ScriptTarget.Latest, true)
const rowClass = ast.statements.find(
  node => ts.isClassDeclaration(node) && node.name?.text === 'PivotAppearanceRowCell'
)
const Cell = new Function(
  'RowCell',
  'pivotTotalBackground',
  ts.transpileModule(rowClass.getText(ast), {
    compilerOptions: { target: ts.ScriptTarget.ES2020 }
  }).outputText + '\nreturn PivotAppearanceRowCell'
)(RowCell, value => value)
function fixture({
  height,
  rowHeight = height,
  y = 120,
  scrollY = 0,
  viewportHeight = 400,
  leaf = true,
  font = 15,
  frozen = 0
}) {
  const cell = Object.create(Cell.prototype)
  const icon = { size: 12, margin: { left: 0, right: 0, top: 0, bottom: 0 } }
  const values = {
    meta: { isLeaf: leaf, height, y },
    headerConfig: { scrollY, viewportHeight },
    textShape: { cfg: { attrs: { x: 60, y: y + height / 2, textAlign: 'center' } } },
    actualTextWidth: 24,
    spreadsheet: {
      options: { style: { cellCfg: { height: rowHeight } } },
      facet: { vScrollBar: null }
    },
    getTextArea: () => ({ x: 0, y, width: 120, height }),
    getTextStyle: () => ({ fontSize: font, textAlign: 'center', textBaseline: 'middle' }),
    getStyle: () => ({ icon }),
    getIconStyle: () => icon,
    getActionIconsCount: () => 0,
    getFrozenFirstRowHeight: () => frozen
  }
  for (const [key, value] of Object.entries(values))
    Object.defineProperty(cell, key, { value, writable: true })
  return cell
}

test('actual project row cell converts S2 top coordinates into stable middle coordinates', () => {
  for (const height of [24, 28, 32])
    for (const font of [13, 15, 18]) {
      const cell = fixture({ height, font })
      const old = RowCell.prototype.getTextPosition.call(cell)
      const actual = cell.getTextPosition()
      assert.equal(actual.y, 120 + height / 2)
      assert.equal(actual.x, old.x, 'horizontal alignment is retained')
      assert.equal(old.y + font / 2, actual.y, 'engine row coordinate denotes text top')
      assert.equal(cell.getIconYPosition() + 6, actual.y)
      assert.equal(cell.getIconPosition().y + 6, actual.y)
      assert.equal(cell.getIconPosition().x, 72)
    }
})

test('partial bottom rows do not shift their labels to squeeze text into the viewport', () => {
  for (const height of [24, 28, 32])
    for (const scrollY of [0, 7, 25]) {
      for (const visible of [1, 8, 15, height - 1, height]) {
        const cell = fixture({ height, scrollY, viewportHeight: 120 + visible - scrollY })
        assert.equal(cell.getTextPosition().y, 120 + height / 2)
      }
    }
})

test('merged dimensions keep the engine viewport adjustment with a corrected middle baseline', () => {
  for (const scrollY of [0, 30, 80, 140])
    for (const frozen of [0, 24]) {
      const cell = fixture({
        height: 192,
        rowHeight: 24,
        y: 0,
        leaf: false,
        scrollY,
        viewportHeight: 96,
        frozen
      })
      const engine = RowCell.prototype.getTextPosition.call(cell)
      assert.equal(cell.getTextPosition().y, engine.y + 7.5)
    }
  const summary = fixture({ height: 28, leaf: false })
  assert.equal(
    summary.getTextPosition().y,
    134,
    'single-row group labels and summaries centre as well'
  )
})
