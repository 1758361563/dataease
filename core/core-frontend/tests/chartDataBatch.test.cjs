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
const api = load('api/chart.ts', {
  '@/utils/dashboardParameterRuntime': runtime,
  '@/config/axios': { default: { post: () => new Promise(resolve => pending.push(resolve)) } },
  '@/utils/CalculateFields': { originNameHandleWithArr() {}, originNameHandleBackWithArr() {} },
  'lodash-es': { cloneDeep: value => JSON.parse(JSON.stringify(value)) },
  '@/store/modules/data-visualization/dvMain': { dvMainStoreWithOut: () => ({ dvInfo: {}, canvasViewInfo: {} }) },
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
  console.log('PASS parallel period queries, stale batches, cleanup isolation, ordinary requests and parameter changes')
}
main().catch(error => { console.error(error); process.exitCode = 1 })
