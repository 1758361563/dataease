const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const ts = require('typescript')
function load(file, imports) {
  const output = ts.transpileModule(fs.readFileSync(path.join(__dirname, '../src', file), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText
  const module = { exports: {} }
  new Function('module', 'exports', 'require', output)(module, module.exports, name => imports[name] || require(name))
  return module.exports
}
const runtime = load('utils/dashboardParameterRuntime.ts', {})
const pending = []
const requests = []
const editor = { dvInfo: {}, canvasViewInfo: {} }
const api = load('api/chart.ts', {
  '@/utils/dashboardParameterRuntime': runtime,
  '@/config/axios': { default: { post: request => {
    requests.push(request)
    return new Promise(resolve => pending.push(resolve))
  } } },
  '@/utils/CalculateFields': { originNameHandleWithArr() {}, originNameHandleBackWithArr() {} },
  'lodash-es': { cloneDeep: value => JSON.parse(JSON.stringify(value)) },
  '@/store/modules/data-visualization/dvMain': { dvMainStoreWithOut: () => editor },
  '@/views/chart/components/js/chartFieldInstance': { ensurePivotDimensionInstances() {} }
})
const chart = { id: 'card', sceneId: 'dashboard', chartExtRequest: {} }
const reply = index => pending[index]({ code: 0, data: { data: { series: [] } } })
async function main() {
  const batch = api.beginChartDataBatch(chart)
  const siblings = [batch.getData(chart), batch.getData(chart), batch.getData(chart)]
  reply(1); await siblings[1] // one period finishing must not invalidate the other periods
  reply(2); reply(0)
  await Promise.all(siblings)
  batch.finish()

  const old = api.beginChartDataBatch(chart)
  const stale = old.getData(chart)
  const rejected = assert.rejects(stale, { name: 'AbortError' })
  const newer = api.beginChartDataBatch(chart)
  const fresh = newer.getData(chart)
  reply(3); await rejected
  old.finish() // stale cleanup must not invalidate the newer render
  reply(4); await fresh; newer.finish()

  const singleOld = api.getData(chart)
  const singleRejected = assert.rejects(singleOld, { name: 'AbortError' })
  const singleNew = api.getData(chart)
  reply(5); reply(6)
  await singleRejected; await singleNew

  const parameterBatch = api.beginChartDataBatch(chart)
  const parameterRequest = parameterBatch.getData(chart)
  const parameterRejected = assert.rejects(parameterRequest, { name: 'AbortError' })
  runtime.dashboardParameterRuntime.applied = { changed: '2026-11-01' }
  reply(7); await parameterRejected; parameterBatch.finish()
  editor.dvInfo = { id: 'dashboard', resourceTable: 'snapshot', calculationConfig: '{"parameters":[]}' }
  editor.canvasViewInfo = { card: chart }
  for (const method of ['getFieldData', 'getDrillFieldData']) {
    const input = { ...chart, resourceTable: 'core', data: { rows: ['keep'] } }
    const before = JSON.stringify(input)
    const index = pending.length
    const result = api[method]({ fieldId: 'category', fieldType: 'xAxisExt', data: input })
    assert.equal(requests[index].data.resourceTable, 'snapshot')
    assert.equal(requests[index].data.sceneId, 'dashboard')
    assert.equal(requests[index].data.calculationConfig, editor.dvInfo.calculationConfig)
    assert.equal(requests[index].data.data, undefined)
    assert.equal(JSON.stringify(input), before, 'candidate queries must not mutate the watched chart')
    pending[index]({ code: 0, data: ['香水', '洗护发'] })
    assert.deepEqual((await result).data, ['香水', '洗护发'])
  }
  // A chart outside the active editor must retain its published context.
  const index = pending.length
  const published = api.getFieldData({ fieldId: 'category', fieldType: 'xAxisExt', data: { id: 'other', resourceTable: 'core' } })
  assert.equal(requests[index].data.resourceTable, 'core')
  assert.equal(requests[index].data.calculationConfig, undefined)
  pending[index]({ code: 0, data: [] }); await published
  console.log('PASS sort and drill candidates carry editor context, preserve chart state and isolate published charts')
  console.log('PASS parallel period queries, stale batches, cleanup isolation, ordinary requests and parameter changes')
}
main().catch(error => { console.error(error); process.exitCode = 1 })
