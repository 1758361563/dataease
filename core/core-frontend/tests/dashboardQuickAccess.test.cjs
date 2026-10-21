const assert = require('node:assert/strict'),
  fs = require('fs'),
  path = require('path'),
  Module = require('module'),
  ts = require('typescript')
function load(file) {
  const mod = new Module(file)
  mod.paths = module.paths
  mod.require = name =>
    name === './companyIds'
      ? load(path.resolve(__dirname, '../src/utils/companyIds.ts'))
      : require(name)
  mod._compile(
    ts.transpileModule(fs.readFileSync(file, 'utf8'), {
      compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS }
    }).outputText,
    file
  )
  return mod.exports
}
const { dashboardShortcuts } = load(path.resolve(__dirname, '../src/utils/dashboardQuickAccess.ts'))
const id = '900000000000000010',
  second = '900000000000000011'
const node = (id, name, extra = {}) => ({
  id,
  name,
  leaf: true,
  weight: 1,
  extraFlag1: 1,
  ...extra
})
const catalog = [
  {
    id: 'folder',
    name: '财务分析',
    children: [
      node(id, '新报表名称'),
      { id: 'nested', name: '月度', children: [node(second, '周月报')] },
      node('revoked', '无权限', { weight: 0 }),
      node('draft', '未发布', { extraFlag1: 0 })
    ]
  }
]
const recent = [
  { id: second, type: 'panel', lastEditTime: 1720000000000, favorite: false },
  { id, type: 'panel', name: '旧名称', favorite: true },
  { id: 'gone', type: 'panel' },
  { id: 'revoked', type: 'panel' },
  { id: 'draft', type: 'panel' },
  { id, type: 'screen' },
  { id, type: 'panel' }
]
const rows = dashboardShortcuts(recent, catalog, 'recent')
assert.deepEqual(
  rows.map(r => r.id),
  [second, id],
  'History order and string IDs are preserved; inaccessible, unpublished and duplicate entries are removed'
)
assert.equal(rows[0].folder, '财务分析 / 月度')
assert.equal(rows[0].viewedAt, 1720000000000)
assert.equal(rows[1].name, '新报表名称', 'Current catalog name wins after rename')
assert.equal(rows[1].favorite, true)
const favorites = dashboardShortcuts(
  [{ id: 'STORE_RECORD_ID', resourceId: id, type: 'panel', lastEditTime: 1720000000000 }],
  catalog,
  'favorite'
)
assert.equal(favorites[0].id, id, 'Store record ID must never be opened as dashboard ID')
assert.equal(
  favorites[0].node,
  catalog[0].children[0],
  'Selection keeps original permission-bearing catalog node'
)
assert.equal(favorites[0].favorite, true)
assert.equal(
  favorites[0].viewedAt,
  undefined,
  'Favorite edit timestamp must never be labeled as viewing time'
)
assert.deepEqual(dashboardShortcuts(recent, [], 'recent'), [])
assert.deepEqual(
  dashboardShortcuts([{ id, type: 'panel', lastEditTime: 'bad' }], catalog, 'recent')[0].viewedAt,
  undefined
)
console.log(
  'Dashboard shortcuts: IDs, permissions, unpublished/deleted resources, nested paths, deduplication and viewing timestamps passed'
)
