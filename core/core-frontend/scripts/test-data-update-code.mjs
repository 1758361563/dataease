// Source compilation and API contract checks only; no browser, backend or database.
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import { build, transform } from 'esbuild'
import { parse, compileScript, compileTemplate, compileStyleAsync } from '@vue/compiler-sfc'

const root = fileURLToPath(new URL('..', import.meta.url))
for (const name of ['DataUpdateDrawer.vue', 'DataUpdateWorkspace.vue', 'index.vue']) {
  const filename = path.join(root, 'src/views/visualized/data/datasource', name)
  const { descriptor, errors } = parse(await readFile(filename, 'utf8'), { filename })
  assert.deepEqual(errors, [], `${name}: parse`)
  const script = compileScript(descriptor, { id: `update-${name}` })
  await transform(script.content, { loader: 'tsx', target: 'es2022' })
  const template = compileTemplate({
    source: descriptor.template.content,
    filename,
    id: `update-${name}`,
    compilerOptions: { bindingMetadata: script.bindings }
  })
  assert.deepEqual(template.errors, [], `${name}: template`)
  if (name !== 'index.vue') {
    for (const style of descriptor.styles) {
      const result = await compileStyleAsync({
        source: style.content,
        filename,
        id: 'data-update',
        scoped: true,
        preprocessLang: style.lang
      })
      assert.deepEqual(result.errors, [], 'Drawer styles compile')
    }
  }
  console.log(`PASS ${name}: script and template compilation`)
}

async function loadApi(name) {
  const bundle = await build({
    entryPoints: [path.join(root, 'src/api/company', name)],
    bundle: true,
    write: false,
    format: 'esm',
    platform: 'node',
    plugins: [
      {
        name: 'request-double',
        setup(builder) {
          builder.onResolve({ filter: /^@\/config\/axios$/ }, () => ({
            path: 'request',
            namespace: 'test'
          }))
          builder.onLoad({ filter: /.*/, namespace: 'test' }, () => ({
            contents: `
        const invoke = async (method, options) => { globalThis.updateRequests.push({method,...options}); return {data: globalThis.updateResponse} }
        export default {get:o=>invoke('GET',o),post:o=>invoke('POST',o),delete:o=>invoke('DELETE',o)}
      `
          }))
        }
      }
    ]
  })
  return import(
    `data:text/javascript;base64,${Buffer.from(bundle.outputFiles[0].text).toString('base64')}`
  )
}
const api = await loadApi('dataUpdate.ts')
const setup = await loadApi('dataUpdateSetup.ts')
globalThis.updateRequests = []
globalThis.updateResponse = { tasks: [], datasources: [], instanceId: 'local', hardDisabled: false }
assert.deepEqual(await api.fetchDataUpdates(), globalThis.updateResponse)
const task = api.newDataUpdate('9007199254740993', '任意宽表')
assert.equal(task.datasourceId, '9007199254740993')
assert.equal(task.enabled, false)
assert.equal(task.allowEmpty, false)
assert.equal(task.trigger, 'MANUAL')
await api.runDataUpdate('task/a', 7, '2026-10-30')
assert.deepEqual(globalThis.updateRequests.at(-1).data, { version: 7, businessDate: '2026-10-30' })
assert.ok(globalThis.updateRequests.at(-1).url.endsWith('/task%2Fa/run'))
await api.deleteDataUpdate('task/a', 7)
assert.equal(globalThis.updateRequests.at(-1).method, 'DELETE')
assert.deepEqual(globalThis.updateRequests.at(-1).params, { version: 7 })
await api.cancelDataUpdate('task/a', 'run/b')
assert.ok(globalThis.updateRequests.at(-1).url.endsWith('/task%2Fa/runs/run%2Fb/cancel'))
const ddlRequest = {
  operationId: 'operation-1',
  datasourceId: task.datasourceId,
  table: '任意表',
  sql: 'CREATE TABLE t(id INT);'
}
await setup.executeUpdateDdl(ddlRequest)
assert.deepEqual(globalThis.updateRequests.at(-1).data, ddlRequest)
await setup.inspectUpdate(task, true)
assert.deepEqual(globalThis.updateRequests.at(-1).params, { preview: true })
assert.equal(globalThis.updateRequests.at(-1).data.datasourceId, task.datasourceId)
await setup.readUpdateStructure(task.datasourceId, '任意表')
assert.deepEqual(globalThis.updateRequests.at(-1).params, {
  datasourceId: task.datasourceId,
  table: '任意表'
})
await setup.runUpdateGroup({ id: 'group/a', version: 5 }, '2026-10-30')
assert.ok(globalThis.updateRequests.at(-1).url.endsWith('/group%2Fa/run'))
assert.deepEqual(globalThis.updateRequests.at(-1).data, { version: 5, businessDate: '2026-10-30' })
await setup.reconcileGroupRun('run/a')
assert.ok(globalThis.updateRequests.at(-1).url.endsWith('/runs/run%2Fa/reconcile'))
delete globalThis.updateRequests
delete globalThis.updateResponse
console.log(
  'PASS data update APIs: IDs, defaults, DDL idempotency payload, preview, groups and encoded paths'
)
