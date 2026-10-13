const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const Module = require('node:module')
const ts = require('typescript')
const { parse, compileScript, compileTemplate } = require('@vue/compiler-sfc')

const identityFile = path.resolve(__dirname, '../src/views/chart/components/js/chartFieldInstance.ts')
const identityModule = new Module(identityFile, module)
identityModule._compile(ts.transpileModule(fs.readFileSync(identityFile, 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText, identityFile)
const { ensurePivotDimensionInstances: ensurePivot, resultKey: pivotKey, formatPivotDate: formatMonth } = identityModule.exports
const pivot = { type: 'table-pivot', xAxis: [{id:'11', dataeaseName:'date',dateStyle:'y'}], xAxisExt:[{id:'11', dataeaseName:'date',dateStyle:'M'}] }
ensurePivot(pivot)
assert.notEqual(pivotKey(pivot.xAxis[0]), pivotKey(pivot.xAxisExt[0]))
assert.equal(pivot.xAxis[0].dateStyle, 'y')
assert.equal(pivot.xAxisExt[0].dateStyle, 'M')
const persistedPivot = JSON.stringify(pivot); ensurePivot(pivot)
assert.equal(JSON.stringify(pivot), persistedPivot)
const reopened = JSON.parse(persistedPivot); ensurePivot(reopened)
assert.equal(JSON.stringify(reopened), persistedPivot)
const uniquePivot = { type:'table-pivot', xAxis:[{id:'11',dataeaseName:'date'}],xAxisExt:[] }; ensurePivot(uniquePivot)
assert.equal(uniquePivot.xAxis[0].fieldInstanceId, undefined)
assert.equal(formatMonth('07',{deType:1,dateStyle:'M'}),'7月')
assert.equal(formatMonth('1',{deType:1,dateStyle:'M',dateShowFormat:'month_number'}),'01')
assert.equal(formatMonth('2026',{deType:1,dateStyle:'y'}),'2026')
assert.equal(formatMonth(null,{deType:1,dateStyle:'M'}),null)
console.log('Pivot dimension identities and month header formatting passed.')

const relativeFile = path.resolve(__dirname, '../src/utils/dashboardRelativeDate.ts')
const relativeModule = new Module(relativeFile, module)
relativeModule._compile(ts.transpileModule(fs.readFileSync(relativeFile, 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
}).outputText, relativeFile)
const preview = (period, offset, position, date, base = 'TODAY') => relativeModule.exports.previewRelativeDate(
  { period, offset, position, base }, 'Asia/Shanghai', new Date(date))
assert.equal(preview('MONTH', 0, 'START', '2026-10-12T00:00:00Z'), '2026-10-01')
assert.equal(preview('MONTH', 0, 'START', '2026-10-01T00:00:00Z', 'YESTERDAY'), '2026-09-01')
assert.equal(preview('MONTH', -1, 'END', '2024-03-31T00:00:00Z'), '2024-02-29')
assert.equal(preview('WEEK', -1, 'END', '2026-10-12T00:00:00Z'), '2026-10-11')
assert.equal(preview('QUARTER', -1, 'END', '2026-01-01T00:00:00Z'), '2025-12-31')
assert.equal(preview('YEAR', 1, 'START', '2026-12-31T00:00:00Z'), '2027-01-01')
assert.equal(preview('YEAR', 0, 'END', '2024-02-01T00:00:00Z'), '2024-12-31')
assert.equal(preview('DAY', -2, 'START', '2026-01-01T00:00:00Z'), '2025-12-30')
assert.equal(preview('DAY', 0, 'START', '2026-10-10T16:00:00Z'), '2026-10-11')
assert.throws(() => preview('MONTH', 0.5, 'START', '2026-01-01T00:00:00Z'), /整数/)
console.log('Relative-date preview calendar and timezone boundaries passed.')

const filename = path.resolve(__dirname, '../src/utils/dashboardParameterRuntime.ts')
const compiled = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
}).outputText
const runtimeModule = new Module(filename, module)
runtimeModule.filename = filename
runtimeModule.paths = Module._nodeModulePaths(path.dirname(filename))
runtimeModule._compile(compiled, filename)
const { dashboardParameterRuntime: runtime, initializeDashboardParameters: initialize,
  applyDashboardParameters: apply, attachDashboardParameters: attach,
  hasDashboardQueryButton: hasButton } = runtimeModule.exports
const config = { version: 1, timezone: 'Asia/Shanghai', parameters: [
  { id: '900000000000000010', name: '期初', type: 'DATE', required: true },
  { id: '900000000000000011', name: '期末', type: 'DATE', required: true }
], datasetFields: [] }
const values = { '900000000000000010': '2026-04-01', '900000000000000011': '2026-10-04' }
initialize('100', 'snapshot', { config, values, reference: '2026-10-10T00:00:00Z' })
runtime.draft['900000000000000011'] = '2026-09-30'
const beforeQuery = { sceneId: '100' }; attach(beforeQuery)
assert.equal(beforeQuery.dashboardParameterValues['900000000000000011'], '2026-10-04')
apply()
const firstCard = { sceneId: '100' }; const secondCard = { sceneId: '100' }
attach(firstCard); attach(secondCard)
assert.equal(firstCard.dashboardParameterValues['900000000000000011'], '2026-09-30')
assert.deepEqual(firstCard.dashboardParameterValues, secondCard.dashboardParameterValues)
assert.equal(firstCard.dashboardParameterReference, secondCard.dashboardParameterReference)
assert.notEqual(firstCard.dashboardParameterValues, secondCard.dashboardParameterValues)
assert.equal(firstCard.resourceTable, 'snapshot')
assert.ok(firstCard.calculationConfig)
runtime.draft['900000000000000010'] = null
assert.throws(apply, /期初/)
assert.equal(runtime.applied['900000000000000010'], '2026-04-01')
runtime.draft = { ...runtime.defaults }; apply()
assert.deepEqual(runtime.applied, values)
initialize('100', 'core', { config, values, reference: '2026-10-10T00:00:00Z' })
const viewer = { sceneId: '100', calculationConfig: 'stale draft' }; attach(viewer)
assert.equal(viewer.calculationConfig, undefined)
const foreign = { sceneId: '200' }; attach(foreign)
assert.equal(foreign.dashboardParameterValues, undefined)
initialize('200', 'core', { config: { ...config, parameters: [] }, values: {}, reference: '2026-10-10T00:00:00Z' })
assert.deepEqual(runtime.applied, {})
assert.equal(hasButton([{ component: 'VQuery', id: 'query' }], { query: { customStyle: { btnList: ['sure'] } } }), true)
assert.equal(hasButton([{ component: 'DeTabs', propValue: [{ componentData: [{ component: 'VQuery', id: 'query' }] }] }], { query: { customStyle: { btnList: ['sure'] } } }), true)
assert.equal(hasButton([{ component: 'VQuery', id: 'query' }], { query: { customStyle: { btnList: ['clear'] } } }), false)
console.log('Parameter draft/apply/reset, batch consistency and dashboard/revision isolation passed.')
const editorContext = runtimeModule.exports.attachDashboardEditorContext
const pendingCard = { id: 'new-card', sceneId: '0', resourceTable: 'core', dashboardParameterValues: { stale: '2026-01-01' }, chartExtRequest: { dashboardParameterValues: { stale: '2026-01-01' } } }
editorContext(pendingCard, { id: '100', resourceTable: 'snapshot', calculationConfig: JSON.stringify(config) }, { 'new-card': {} })
assert.equal(pendingCard.resourceTable, 'snapshot')
assert.equal(pendingCard.sceneId, '100')
assert.deepEqual(JSON.parse(pendingCard.calculationConfig), config)
assert.equal(pendingCard.dashboardParameterValues, undefined)
assert.equal(pendingCard.chartExtRequest.dashboardParameterValues, undefined)
const publishedCard = { id: 'saved-card', resourceTable: 'core' }
editorContext(publishedCard, { id: '100', resourceTable: 'core' }, { 'saved-card': {} })
assert.equal(publishedCard.resourceTable, 'core')
const foreignCard = { id: 'other-card', sceneId: '200', resourceTable: 'core' }
editorContext(foreignCard, { id: '100', resourceTable: 'snapshot' }, { 'new-card': {} })
assert.equal(foreignCard.sceneId, '200')
const unsavedCard = { id: 'draft-card', sceneId: 0, resourceTable: 'core' }
editorContext(unsavedCard, { resourceTable: 'snapshot' }, { 'draft-card': {} })
assert.equal(unsavedCard.sceneId, null)
assert.equal(unsavedCard.resourceTable, 'snapshot')
console.log('Editor query revisions, new-card placeholders and published/foreign isolation passed.')

const { stripDashboardQueryState: strip, dashboardTemplateStyle: templateStyle,
  beginDashboardQuery: begin } = runtimeModule.exports
initialize('100', 'snapshot', { config, values, reference: '2026-10-10T00:00:00Z' })
const exportChart = { id: '1', sceneId: '100', chartExtRequest: { cache: true } }
attach(exportChart)
assert.deepEqual(exportChart.chartExtRequest.dashboardParameterValues, values)
assert.equal(exportChart.chartExtRequest.cache, false)
runtime.draft['900000000000000011'] = '2026-01-01'
const pendingExport = { sceneId: '100', chartExtRequest: {} }; attach(pendingExport)
assert.equal(pendingExport.dashboardParameterValues['900000000000000011'], '2026-10-04')
const packaged = strip(JSON.parse(JSON.stringify(exportChart)))
assert.equal(packaged.dashboardParameterValues, undefined)
assert.equal(packaged.chartExtRequest.dashboardParameterValues, undefined)
assert.equal(packaged.calculationConfig, undefined)
assert.deepEqual(JSON.parse(templateStyle('{"dashboard":{}}', JSON.stringify(config))).dashboardCalculation, config)
const older = begin(exportChart); const latest = begin(exportChart)
assert.throws(older.assertCurrent, { name: 'AbortError' }); assert.doesNotThrow(latest.assertCurrent)
apply()
assert.throws(latest.assertCurrent, { name: 'AbortError' })
older.finish(); latest.finish()
console.log('Export snapshots, template definitions and stale-response isolation passed.')

const { rebindDashboardTemplate: rebind } = runtimeModule.exports
const templateConfig = { ...config, datasetFields: [{ id: '13', datasetGroupId: '10', name: 'YTD', groupType: 'd', deType: 2,
  expression: "if([11]>=[DVP.900000000000000010],1,0)+'[11]'" }] }
const sourceFields = [{ id: '11', name: '取值时间', deType: 1, dataeaseName: 'old_date' }]
const targetFields = [{ id: '22', name: '取值时间', deType: 1, dataeaseName: 'new_date' }]
const rebound = rebind(JSON.stringify(templateConfig), '10', '20', sourceFields, targetFields,
  { sourceTableId: '10', xAxis: [{ id: '11', dataeaseName: 'old_date' }], title: 'Card11' })
assert.equal(JSON.parse(rebound.config).datasetFields[0].datasetGroupId, '20')
assert.ok(JSON.parse(rebound.config).datasetFields[0].expression.includes('[22]'))
assert.ok(JSON.parse(rebound.config).datasetFields[0].expression.includes("'[11]'"))
assert.equal(rebound.chart.xAxis[0].id, '22'); assert.equal(rebound.chart.xAxis[0].dataeaseName, 'new_date')
assert.equal(rebound.chart.title, 'Card11')
assert.throws(() => rebind(JSON.stringify(templateConfig), '10', '20', sourceFields, [], {}), /取值时间/)
console.log('Style-template dataset binding and incompatible-field rejection passed.')

for (const relative of [
  'components/visualization/DashboardParameterBar.vue',
  'components/data-visualization/canvas/DePreview.vue',
  'custom-component/v-query/Component.vue',
  'views/chart/components/editor/index.vue',
  'views/data-visualization/index.vue',
  'views/dashboard/index.vue',
  'components/dashboard/DbToolbar.vue',
  'views/chart/components/editor/drag-item/DimensionItem.vue',
  'views/visualized/data/dataset/form/CalcFieldEdit.vue'
]) {
  const file = path.resolve(__dirname, '../src', relative)
  const { descriptor, errors } = parse(fs.readFileSync(file, 'utf8'), { filename: file })
  assert.deepEqual(errors, [], relative)
  const script = compileScript(descriptor, { id: relative })
  const template = compileTemplate({ source: descriptor.template.content, filename: file,
    id: relative, compilerOptions: { bindingMetadata: script.bindings } })
  assert.deepEqual(template.errors, [], relative)
}
console.log('All changed Vue components compile successfully.')
