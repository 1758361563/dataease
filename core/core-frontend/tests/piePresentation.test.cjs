const fs = require('node:fs')
const path = require('node:path')
const Module = require('node:module')
const assert = require('node:assert/strict')
const ts = require('typescript')
const { parse, compileScript, compileTemplate } = require('@vue/compiler-sfc')
const file = path.resolve(
  __dirname,
  '../src/views/chart/components/js/panel/charts/pie/piePresentation.ts'
)
const mod = new Module(file)
const palettePath = path.resolve(
  __dirname,
  '../src/views/chart/components/editor/util/chartPalettes.ts'
)
const paletteModule = new Module(palettePath)
paletteModule._compile(
  ts.transpileModule(fs.readFileSync(palettePath, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText,
  palettePath
)
mod.require = request =>
  request.endsWith('chartPalettes') ? paletteModule.exports : require(request)
assert.equal(paletteModule.exports.CHART_PALETTES.length, 3)
assert.ok(
  paletteModule.exports.CHART_PALETTES.every(
    palette =>
      palette.colors.length === 8 && palette.colors.every(color => /^#[0-9A-F]{6}$/.test(color))
  )
)
assert.deepEqual(
  paletteModule.exports.CHART_PALETTES.map(palette => palette.colors[0]),
  ['#4878D6', '#4E76CC', '#718DA6']
)
assert.equal(paletteModule.exports.normalizePieLayout('legacy'), 'large')
assert.equal(paletteModule.exports.normalizePieLayout(undefined), 'large')
assert.equal(paletteModule.exports.normalizePieLayout('ranking'), 'ranking')
mod._compile(
  ts.transpileModule(fs.readFileSync(file, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText,
  file
)
const {
  stablePieColor,
  pieColorMap,
  readablePieTooltip,
  largePiePresentation,
  shortenPieName,
  PIE_CATEGORY_COLORS,
  preparePiePresentation
} = mod.exports
const pieSource = fs.readFileSync(
  path.resolve(__dirname, '../src/views/chart/components/js/panel/charts/pie/pie.ts'),
  'utf8'
)
const pieAst = ts.createSourceFile('pie.ts', pieSource, ts.ScriptTarget.Latest, true)
let layoutCallback
let colorMethod
function findLayout(node) {
  if (ts.isMethodDeclaration(node) && node.name.getText(pieAst) === 'configSingleDimensionColor')
    colorMethod = node.getText(pieAst)
  if (
    ts.isCallExpression(node) &&
    node.expression.getText(pieAst) === 'G2.registerGeometryLabelLayout'
  )
    layoutCallback = node.arguments[1].getText(pieAst)
  ts.forEachChild(node, findLayout)
}
findLayout(pieAst)
const calls = []
const layoutCode = ts.transpileModule(`const layout=${layoutCallback}`, {
  compilerOptions: { target: ts.ScriptTarget.ES2020 }
}).outputText
const safeLayout = new Function('G2', layoutCode + '\nreturn layout')({
  getGeometryLabelLayout: type => items => {
    assert.ok(items.every(Boolean))
    calls.push(type)
  }
})
const data = Array.from({ length: 20 }, (_, i) => ({ field: `分类${i}`, value: 20 - i }))
const before = JSON.stringify(data)
for (const palette of paletteModule.exports.CHART_PALETTES) {
  const rows = preparePiePresentation(data).sectors
  const colors = pieColorMap(rows, palette.colors)
  assert.deepEqual(
    rows.slice(0, 8).map(row => colors.get(row.pieKey)),
    palette.colors
  )
  assert.equal(colors.get(rows[8].pieKey), palette.colors[0])
  const tooltip = readablePieTooltip({ showTitle: true }, rows, 10)
  assert.equal(tooltip.title(rows[0].pieKey, {}), rows[0].field)
  assert.equal(tooltip.domStyles['g2-tooltip'].fontSize, '14px')
  assert.equal(tooltip.domStyles['g2-tooltip-title'].fontSize, '16px')
  assert.equal(tooltip.domStyles['g2-tooltip'].padding, '8px 10px')
  assert.equal(tooltip.domStyles['g2-tooltip-value'].flex, '0 1 auto')
  assert.equal(tooltip.domStyles['g2-tooltip-value'].textAlign, 'left')
  assert.equal(tooltip.domStyles['g2-tooltip-list-item'].justifyContent, 'flex-start')
}
let drawMethod, highlightCode
function findHover(node) {
  if (ts.isMethodDeclaration(node) && node.name.getText(pieAst) === 'drawChart') drawMethod = node
  if (ts.isVariableDeclaration(node) && node.name.getText(pieAst) === 'highlight')
    highlightCode = node.initializer.getText(pieAst)
  ts.forEachChild(node, findHover)
}
findHover(pieAst)
for (const event of ['interval:mouseenter', 'interval:mouseleave']) {
  assert.ok(
    drawMethod.body.statements.some(
      statement =>
        ts.isExpressionStatement(statement) &&
        ts.isCallExpression(statement.expression) &&
        statement.expression.arguments[0]?.text === event
    ),
    'hover listeners must bind even when the big pie has no Vue panel'
  )
}
const activeKey = { value: null }
const states = { active: new Set(), inactive: new Set() }
const mockChart = {
  setState(name, predicate, enabled) {
    for (const pieKey of ['a', 'b'])
      if (predicate({ pieKey })) enabled ? states[name].add(pieKey) : states[name].delete(pieKey)
  }
}
const hoverCode = ts.transpileModule(`const highlight=${highlightCode}`, {
  compilerOptions: { target: ts.ScriptTarget.ES2020 }
}).outputText
const highlight = new Function('activeKey', 'newChart', hoverCode + '\nreturn highlight')(
  activeKey,
  mockChart
)
highlight('a')
assert.deepEqual([...states.active], ['a'])
assert.deepEqual([...states.inactive], ['b'])
highlight(null)
assert.equal(states.active.size, 0)
assert.equal(states.inactive.size, 0)
assert.equal(readablePieTooltip(false, []), false)
const mixed = [
  { field: '其他', value: 40, dimensionList: [{ value: '其他' }], quotaList: [{ id: 'amount' }] },
  { field: null, value: 30 },
  { field: 'null', value: 20 },
  { field: '', value: 10 },
  { field: 'bad', value: -4 },
  { field: 'invalid', value: NaN }
]
const mixedBefore = JSON.stringify(mixed)
const all = preparePiePresentation(mixed)
assert.equal(all.sectors.length, 4)
assert.equal(all.total, 100)
assert.equal(all.excluded, 2)
assert.equal(new Set(all.sectors.map(row => row.pieKey)).size, 4)
const merged = preparePiePresentation(mixed, true, 1)
const colorCode = ts.transpileModule(`class Test extends Object { ${colorMethod} }`, {
  compilerOptions: { target: ts.ScriptTarget.ES2020 }
}).outputText
const colorRenderer = new Function(
  'parseJson',
  'PIE_CATEGORY_COLORS',
  'pieColorMap',
  'hexColorToRGBA',
  colorCode + '\nreturn new Test()'
)(
  value => value,
  PIE_CATEGORY_COLORS,
  pieColorMap,
  hex => hex
)
const colored = colorRenderer.configSingleDimensionColor(
  {
    customAttr: {
      basicStyle: { pieLayout: 'ranking', seriesColor: [{ id: '其他', color: '#123456' }] }
    }
  },
  { data: merged.sectors }
)
for (const palette of paletteModule.exports.CHART_PALETTES) {
  const options = colorRenderer.configSingleDimensionColor(
    {
      customAttr: {
        basicStyle: {
          pieLayout: 'large',
          colors: palette.colors,
          seriesColor: [],
          piePalette: 'categorical'
        }
      }
    },
    { data: all.sectors }
  )
  assert.equal(
    options.color({ pieKey: all.sectors[0].pieKey }),
    palette.colors[0],
    'saved legacy piePalette must not override the public color scheme'
  )
}
assert.equal(
  colored.color({ pieKey: merged.sectors[0].pieKey }),
  '#123456',
  'engine receives only colorField; resolve original category first'
)
assert.equal(
  colored.color({ pieKey: merged.sectors[1].pieKey }),
  '#94a3b8',
  'virtual Other must not steal the real Other color'
)
assert.equal(merged.sectors.length, 2)
assert.equal(merged.sectors[0].field, '其他')
assert.equal(merged.sectors[1].field, '其他')
assert.notEqual(merged.sectors[0].pieKey, merged.sectors[1].pieKey)
assert.equal(merged.sectors[1].value, 60)
assert.deepEqual(
  merged.members.map(row => row.field),
  [null, 'null', '']
)
assert.deepEqual(
  merged.sectors[1].dimensionList,
  [],
  'virtual aggregate cannot masquerade as a real filter'
)
assert.equal(
  merged.sectors.reduce((sum, row) => sum + row.value, 0),
  all.total
)
assert.equal(JSON.stringify(mixed), mixedBefore)
assert.equal(preparePiePresentation(mixed, true, 100).members.length, 0)
assert.equal(preparePiePresentation([], true).sectors.length, 0)
const large = largePiePresentation(data, 900, 450)
assert.equal(data.filter(large.showLabel).length, 8)
assert.equal(JSON.stringify(data), before, 'label selection never removes or reorders sectors')
assert.equal(large.compact, false)
const small = largePiePresentation(data, 360, 260)
assert.equal(small.compact, true)
assert.equal(data.filter(small.showLabel).length, 3)
assert.equal(data.filter(largePiePresentation(data, 900, 450, 12, 4).showLabel).length, 4)
assert.equal(largePiePresentation([], 900, 450).showLabel({ field: 'empty' }), false)
for (const count of [1, 2])
  assert.equal(
    data.slice(0, count).filter(largePiePresentation(data.slice(0, count), 900, 450).showLabel)
      .length,
    count
  )
const invalid = [
  { field: 'negative', value: -1 },
  { field: 'zero', value: 0 },
  { field: 'invalid', value: NaN }
]
assert.equal(invalid.filter(largePiePresentation(invalid, 900, 450).showLabel).length, 0)
assert.equal(
  largePiePresentation(
    [
      { field: null, value: 2 },
      { field: 'null', value: 1 }
    ],
    900,
    450,
    12,
    1
  ).showLabel({ field: 'null' }),
  false
)
const original = new Map(data.map(d => [d.field, stablePieColor(d.field, PIE_CATEGORY_COLORS)]))
for (const datum of data.slice().reverse().slice(3))
  assert.equal(stablePieColor(datum.field, PIE_CATEGORY_COLORS), original.get(datum.field))
assert.equal(
  stablePieColor('分类1', PIE_CATEGORY_COLORS, [{ id: '分类1', color: '#123456' }]),
  '#123456'
)
assert.equal(stablePieColor('分类1', []), stablePieColor('分类1', PIE_CATEGORY_COLORS))
assert.equal(shortenPieName(null, 100, 12), '未分类')
assert.ok(shortenPieName('一个很长很长的分类名称用于验证截断', 100, 12).endsWith('…'))
for (const relative of [
  '../src/views/chart/components/editor/editor-style/components/BasicStyleSelector.vue',
  '../src/views/chart/components/editor/editor-style/components/CustomColorStyleSelect.vue',
  '../src/views/chart/components/js/panel/charts/pie/PiePresentationPanel.vue',
  '../src/views/chart/components/views/components/ChartComponentG2Plot.vue'
]) {
  const filename = path.resolve(__dirname, relative)
  const { descriptor, errors } = parse(fs.readFileSync(filename, 'utf8'), { filename })
  assert.deepEqual(errors, [])
  const script = compileScript(descriptor, { id: 'pie-test' })
  const template = compileTemplate({
    source: descriptor.template.content,
    filename,
    id: 'pie-test',
    compilerOptions: { bindingMetadata: script.bindings }
  })
  assert.deepEqual(template.errors, [])
}
;(async () => {
  await safeLayout([null, { id: 'one' }, null], [], [], {}, { compact: false })
  assert.deepEqual(calls, ['pie-spider', 'hide-overlap', 'limit-in-canvas'])
  calls.length = 0
  await safeLayout([null, { id: 'one' }], [], [], {}, { compact: true })
  assert.deepEqual(calls, ['hide-overlap', 'limit-in-canvas'])
  console.log(
    'Pie presentation: label budget, data preservation, stable colors, null identity, safe sequential layout and Vue compilation passed.'
  )
})().catch(error => {
  console.error(error)
  process.exitCode = 1
})
