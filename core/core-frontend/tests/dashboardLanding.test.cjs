const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const Module = require('node:module')
const ts = require('typescript')
const file = path.resolve(__dirname, '../src/store/modules/permission.ts')
const state = { getRouters: [] }
const mod = new Module(file)
mod.paths = module.paths
mod.require = name => {
  if (name === 'pinia') return { defineStore: () => () => state }
  if (name === '@/router') return { routes: [] }
  if (name === '@/router/establish') return { generateRoutesFn2: x => x }
  if (name === '../index') return { store: {} }
  if (name === 'lodash-es') return { cloneDeep: x => structuredClone(x) }
  if (name.endsWith('.vue')) return {}
  throw new Error('Unexpected dependency: ' + name)
}
mod._compile(
  ts.transpileModule(fs.readFileSync(file, 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS }
  }).outputText,
  file
)
const { getFirstAuthMenu, pathValid } = mod.exports
const menu = (path, child) => ({ path, children: [{ path: child }] })
state.getRouters = [
  menu('/workbranch', 'index'),
  menu('/data', 'dataset'),
  menu('/panel', 'index'),
  menu('/screen', 'index')
]
assert.equal(
  getFirstAuthMenu(),
  '/panel/index',
  'Dashboard is preferred regardless of backend menu order'
)
assert.equal(
  pathValid('/screen/index'),
  true,
  'Hiding the navigation must retain screen route access'
)
state.getRouters = [menu('/workbranch', 'index'), menu('/data', 'dataset')]
assert.equal(
  getFirstAuthMenu(),
  '/data/dataset',
  'Users without a dashboard menu fall back to an authorized route, never the retired homepage'
)
state.getRouters = [menu('/workbranch', 'index')]
assert.equal(
  getFirstAuthMenu(),
  null,
  'No redirect loop through a retired homepage when no usable route exists'
)
console.log(
  'Dashboard landing: preferred route, authorization fallback, retired homepage and retained screen access passed'
)
