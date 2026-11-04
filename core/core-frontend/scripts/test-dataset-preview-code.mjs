// Code-only checks: no application, database, or browser is started.
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import vm from 'node:vm'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { transform } from 'esbuild'
import { parse, compileScript, compileTemplate, compileStyleAsync } from '@vue/compiler-sfc'
const require = createRequire(import.meta.url)
const vue = require('vue')
const cloneDeep = value => JSON.parse(JSON.stringify(value))
const cleanups = []
async function evaluate(source, imports) {
  const { code } = await transform(source, { loader: 'ts', format: 'cjs', target: 'es2022' })
  const module = { exports: {} }
  vm.runInNewContext(code, {
    module,
    exports: module.exports,
    require: id => imports(id),
    AbortController,
    crypto: { getRandomValues: globalThis.crypto.getRandomValues.bind(globalThis.crypto) },
    setTimeout,
    clearTimeout,
    console
  })
  return module.exports
}
const state = await evaluate(
  await fs.readFile(
    new URL('../src/views/visualized/data/dataset/previewState.ts', import.meta.url),
    'utf8'
  ),
  () => ({})
)
const requests = []
const api = {
  queryDatasetPreview(query, mode = 'previewQuery', signal) {
    return new Promise((resolve, reject) => requests.push({ query, mode, signal, resolve, reject }))
  },
  encodePreviewQuery: cloneDeep,
  exportDatasetData: async () => ({ status: 200, data: { code: 0 } })
}
const components = {}
for (const name of [
  'DatasetPreview',
  'DatasetFilterStudio',
  'RestoreFieldNames',
  'DatasetFieldList',
  'form/index',
  'form/FieldMore'
]) {
  const filename = new URL(`../src/views/visualized/data/dataset/${name}.vue`, import.meta.url)
  const { descriptor, errors } = parse(await fs.readFile(filename, 'utf8'))
  assert.equal(errors.length, 0)
  const script = compileScript(descriptor, { id: name })
  const template = compileTemplate({
    source: descriptor.template.content,
    filename: fileURLToPath(filename),
    id: name,
    compilerOptions: { bindingMetadata: script.bindings }
  })
  assert.equal(template.errors.length, 0)
  for (const style of descriptor.styles) {
    const result = await compileStyleAsync({
      source: style.content.replaceAll('@/','' + fileURLToPath(new URL('../src/', import.meta.url)).replaceAll('\\', '/')),
      filename: fileURLToPath(filename),
      id: name,
      scoped: true,
      preprocessLang: style.lang
    })
    assert.equal(result.errors.length, 0, `${name}: ${result.errors.map(String).join("; ")}`)
  }
  await transform(script.content, { loader: 'tsx', target: 'es2022' })
  if (!['DatasetPreview', 'DatasetFilterStudio'].includes(name)) continue
  components[name] = (
    await evaluate(script.content, id => {
      if (id === 'vue') return { ...vue, onBeforeUnmount: fn => cleanups.push(fn) }
      if (id === 'lodash-es') return { cloneDeep }
      if (id === '@/api/dataset') return api
      if (id === './previewState') return state
      if (id === 'element-plus-secondary') return { ElMessage: { error() {}, success() {} } }
      return {}
    })
  ).default
}
const flush = async () => {
  await vue.nextTick()
  await new Promise(resolve => setImmediate(resolve))
}
const scope = vue.effectScope()
const props = vue.reactive({
  datasetId: '1',
  active: true,
  fields: [],
  name: 'test',
  canExport: true
})
const fields = [{ id: '1', name: '日期', dataeaseName: 'd', deType: 0 }]
const screen = scope.run(() => components.DatasetPreview.setup(props, { expose() {}, emit() {} }))
assert.equal(requests.filter(r => r.mode === 'previewCount').length, 0)
requests.find(r => r.mode === 'previewFields').resolve(fields)
await flush()
assert.equal(
  screen.available.value.length,
  1,
  'fields appear before the slow data request finishes'
)
assert.equal(screen.loading.value, true)
requests
  .find(r => r.mode === 'previewQuery')
  .resolve({ data: { fields, data: [{ d: 'first' }] }, hasMore: true })
await flush()
assert.equal(screen.rows.value[0].d, 'first')
const counting = screen.countRows()
requests.at(-1).resolve({ total: 862223 })
await counting
assert.equal(screen.total.value, 862223)
screen.page.value = 2
const next = screen.refresh()
assert.equal(requests.filter(r => r.mode === 'previewCount').length, 1, 'paging never recounts')
requests.at(-1).resolve({ data: { fields, data: [{ d: 'second' }] }, hasMore: false })
await next
assert.equal(screen.total.value, 862223, 'same-filter count is reused')
const older = screen.refresh(),
  oldRequest = requests.at(-1)
const newer = screen.refresh(),
  newRequest = requests.at(-1)
assert.equal(oldRequest.signal.aborted, true)
newRequest.resolve({ data: { fields, data: [{ d: 'newest' }] }, hasMore: false })
await newer
oldRequest.resolve({ data: { fields, data: [{ d: 'stale' }] }, hasMore: true })
await older
assert.equal(screen.rows.value[0].d, 'newest', 'late results cannot overwrite the current query')
const cancelled = screen.refresh(),
  cancelledRequest = requests.at(-1)
screen.cancelPreview()
assert.equal(cancelledRequest.signal.aborted, true)
assert.equal(screen.loading.value, false)
cancelledRequest.reject(new Error('cancelled'))
await cancelled
assert.match(screen.error.value, /取消/)
const studioProps = vue.reactive({
  modelValue: false,
  fields,
  rules: [],
  logic: 'and',
  query: () => ({ id: '1' })
})
scope.run(() => components.DatasetFilterStudio.setup(studioProps, { expose() {}, emit() {} }))
const before = requests.filter(r => r.mode === 'previewCount').length
studioProps.modelValue = true
await flush()
assert.equal(
  requests.filter(r => r.mode === 'previewCount').length,
  before,
  'opening filters does not trigger a count'
)
studioProps.modelValue = false
await flush()
assert.equal(requests.at(-1).signal.aborted, true, 'closing filters cancels candidate lookup')
cleanups.forEach(fn => fn())
scope.stop()

const http = []
const realApi = await evaluate(
  await fs.readFile(new URL('../src/api/dataset.ts', import.meta.url), 'utf8'),
  id => {
    if (id === '@/config/axios')
      return {
        post(options) {
          http.push(options)
          return Promise.resolve({ code: 0, data: {} })
        }
      }
    if (id === 'lodash-es') return { cloneDeep }
    return { originNameHandle() {} }
  }
)
const controller = new AbortController()
const call = realApi.queryDatasetPreview({ id: '1' }, 'previewQuery', controller.signal)
assert.equal(http[0].signal, controller.signal)
assert.equal(http[0].timeout, 20000)
controller.abort()
assert.equal(http[1].url, '/datasetData/previewCancel')
assert.equal(http[1].data.queryId, http[0].data.queryId)
await call
console.log(
  'PASS: Vue compilation/styles, metadata independence, on-demand count, paging reuse, stale-response guard, cancellation and API request identity'
)
