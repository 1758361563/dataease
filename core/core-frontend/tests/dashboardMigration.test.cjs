const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path'), Module = require('node:module')
const ts = require('typescript')
const file = path.resolve(__dirname, '../src/utils/dashboardMigration.ts')
const mod = new Module(file)
mod._compile(ts.transpileModule(fs.readFileSync(file, 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
}).outputText, file)
const { parseDashboardMigration, flattenMigrationNodes } = mod.exports
const fixture = {
  dvType: 'dashboard', version: 3, name: 'test', componentData: '[]', canvasStyleData: '{}',
  dynamicData: JSON.stringify({ a: { id: '900000000000000011', data: { rows: ['private business sample'] } } }),
  appData: JSON.stringify({ datasetGroupsInfo: [{id:'900000000000000012'}], datasetTablesInfo:[],
    datasetTableFieldsInfo:[], visualizationInfo:'{}', datasourceInfo:[{configuration:'private credential'}],
    datasourceTaskInfo:[{token:'private token'}] }), snapshot: 'private screenshot'
}
const parsed = parseDashboardMigration(JSON.stringify(fixture))
assert.equal(parsed.datasets[0].id, '900000000000000012')
assert.equal(JSON.parse(parsed.pack.appData).datasourceInfo[0].configuration, null)
assert.deepEqual(JSON.parse(parsed.pack.appData).datasourceTaskInfo, [])
assert.equal(JSON.parse(parsed.pack.dynamicData).a.data, undefined)
assert.equal(parsed.pack.snapshot, '')
for (const patch of [{dvType:'dataV'},{version:99},{componentData:'{}'},{dynamicData:'[]'}, {appData:'{}'}]) {
  assert.throws(()=>parseDashboardMigration(JSON.stringify({...fixture,...patch})))
}
assert.throws(()=>parseDashboardMigration('invalid json'))
const nodes=[{id:'0',name:'folder',leaf:false,children:[{id:'900000000000000012',name:'dataset',leaf:true}]}]
assert.deepEqual(flattenMigrationNodes(nodes),[{id:'900000000000000012',name:'dataset'}])
assert.deepEqual(flattenMigrationNodes(nodes,true),[{id:'0',name:'folder'}])
console.log('Dashboard migration: invalid files rejected, IDs preserved, credentials/tasks/results stripped, choices flattened')
