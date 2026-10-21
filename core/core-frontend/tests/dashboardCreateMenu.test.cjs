const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path'), ts = require('typescript'), vm = require('node:vm')
const source=fs.readFileSync(path.resolve(__dirname,'../src/views/common/DeResourceTree.vue'),'utf8')
const handler=source.match(/const handleCreateCommand = \(command: string\) => \{[\s\S]*?\n\}/)[0]
const navigations=[], creations=[]; let opens=0
const context={exports:{}, router:{push:route=>navigations.push(route)},isEmbedded:{value:false},appImportDialog:{value:{open:()=>opens++}},addOperation:(...args)=>creations.push(args)}
vm.runInNewContext(ts.transpileModule(handler+'\nexport { handleCreateCommand }',{compilerOptions:{module:ts.ModuleKind.CommonJS}}).outputText,context)
const create=context.exports.handleCreateCommand
create('newLeaf')
assert.equal(navigations[0].path,'/dashboard');assert.equal(navigations[0].query.opt,'create')
assert.equal(creations.length,0,'Standalone creation must not rely on popup/optional plugin')
create('newFolder');assert.equal(creations[0][0],'newFolder');assert.equal(creations[0][2],'folder')
create('template');assert.equal(opens,1)
context.isEmbedded.value=true;create('newLeaf');assert.equal(creations[1][0],'newLeaf')
console.log('Dashboard create menu: standalone uses current-page editor; folder, template and embedded actions preserved')
