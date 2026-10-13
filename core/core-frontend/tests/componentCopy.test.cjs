const fs = require('node:fs')
const path = require('node:path')
const Module = require('node:module')
const assert = require('node:assert/strict')
const ts = require('typescript')
const { reactive, toRef } = require('vue')
const { parse, compileScript, compileTemplate } = require('@vue/compiler-sfc')
const clone = value => JSON.parse(JSON.stringify(value))
const events = []
let sequence = 0, snapshots = 0
const dv = reactive({ curComponent:null, curComponentIndex:-1, curMultiplexingComponents:{}, dvInfo:{type:'dashboard'}, pcMatrixCount:{x:24}, canvasStyleData:{scale:100}, componentData:[], canvasViewInfo:{}, multiplexingStyleAdapt:false })
const compose = { areaData:{components:[]} }
// Exercise the actual canvas insertion and view-definition cloning methods with isolated state.
const mainFile=path.resolve(__dirname,'../src/store/modules/data-visualization/dvMain.ts')
const mainSource=ts.createSourceFile(mainFile,fs.readFileSync(mainFile,'utf8'),ts.ScriptTarget.Latest,true)
function storeMethod(name) {
  let method
  function visit(node) { if(ts.isMethodDeclaration(node) && node.name.getText(mainSource)===name) method=node; ts.forEachChild(node,visit) }
  visit(mainSource)
  assert.ok(method,name)
  const code=ts.transpileModule(`function ${method.getText(mainSource)}`,{compilerOptions:{target:ts.ScriptTarget.ES2020}}).outputText
  return new Function('deepCopy',`${code}; return ${name}`)(clone)
}
dv.addCopyComponent=storeMethod('addCopyComponent')
dv.updateCopyCanvasView=storeMethod('updateCopyCanvasView')
dv.setCurComponent = ({component,index}) => { dv.curComponent=component; dv.curComponentIndex=index }
const stubs = {
  pinia:{ defineStore:(_name, spec) => { const state=spec.state(); for(const [key, action] of Object.entries(spec.actions)) state[key]=action.bind(state); return () => state }, storeToRefs: store => Object.fromEntries(Object.keys(store).map(key=>[key,toRef(store,key)])) },
  './dvMain':{dvMainStoreWithOut:()=>dv},
  './contextmenu':{contextmenuStoreWithOut:()=>reactive({menuTop:0,menuLeft:0})},
  '@/utils/generateID':{generateID:()=>`new-${++sequence}`},
  '@/utils/utils':{deepCopy:clone}, '../../index':{store:{}},
  '@/utils/eventBus':{default:{emit:(name,card)=>events.push({type:'event',name,card})}},
  '@/utils/canvasStyle':{adaptCurThemeCommonStyle:()=>{}},
  '@/store/modules/data-visualization/compose':{composeStoreWithOut:()=>compose},
  '@/store/modules/data-visualization/snapshot':{snapshotStoreWithOut:()=>({recordSnapshotCache:()=>snapshots++})},
  '@/utils/canvasUtils':{maxYComponentCount:()=>0}
}
const filename=path.resolve(__dirname,'../src/store/modules/data-visualization/copy.ts')
const loaded=new Module(filename,module)
loaded.require=name=>Object.prototype.hasOwnProperty.call(stubs,name)?stubs[name]:require(name)
loaded._compile(ts.transpileModule(fs.readFileSync(filename,'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText,filename)
const store=loaded.exports.copyStoreWithOut()
const settle=()=>new Promise(resolve=>setTimeout(resolve,60))
async function run() {
  const source={id:'card',component:'UserView',canvasId:'canvas-main',y:1,sizeY:4,style:{top:0,left:0}}
  dv.canvasViewInfo.card={id:'card',tableId:'10',sceneId:'100',resourceTable:'snapshot',xAxis:[{id:'11',fieldInstanceId:'year',dateStyle:'y'}],xAxisExt:[{id:'11',fieldInstanceId:'month',dateStyle:'M',dateShowFormat:'month_name'}],yAxis:[{id:'20',originName:'sum(if([DVP.10],1,0))'}]}
  dv.componentData=[source]; dv.curComponent=null
  store.copy(source); store.paste(false); await settle()
  assert.equal(dv.componentData.length,2)
  assert.notEqual(dv.componentData[1].id,source.id)
  assert.equal(source.y,1)
  const copiedView=dv.canvasViewInfo[dv.componentData[1].id]
  assert.equal(copiedView.id,dv.componentData[1].id)
  assert.deepEqual(copiedView.xAxis,dv.canvasViewInfo.card.xAxis)
  assert.deepEqual(copiedView.xAxisExt,dv.canvasViewInfo.card.xAxisExt)
  assert.deepEqual(copiedView.yAxis,dv.canvasViewInfo.card.yAxis)
  copiedView.xAxis[0].dateStyle='y_M'
  assert.equal(dv.canvasViewInfo.card.xAxis[0].dateStyle,'y')
  assert.equal(snapshots,1)
  const other={id:'other',component:'UserView',canvasId:'canvas-main'}
  dv.curComponent=other
  store.copy(source)
  assert.equal(store.copyData.data[0].id,'card', 'menu source must win over unrelated selection')
  store.copy()
  assert.equal(store.copyData.data[0].id,'other', 'keyboard copy retains selected-component behavior')
  const nested={...source,id:'nested',canvasId:'tab--page'}
  dv.componentData=[{id:'tab',component:'DeTabs',canvasId:'canvas-main',propValue:[{name:'page',componentData:[nested]}]}]
  dv.curComponent=null
  store.copy(nested); store.paste(false); await settle()
  const children=dv.componentData[0].propValue[0].componentData
  assert.equal(children.length,2)
  assert.equal(children[1].canvasId,'tab--page')
  assert.equal(dv.curComponentIndex,1)
  assert.ok(events.some(event=>event.name==='addDashboardItem-tab--page'))
  store.copy(dv.componentData[0]); store.paste(false); await settle()
  const copiedTab=dv.componentData[1]
  assert.notEqual(copiedTab.id,'tab')
  assert.equal(copiedTab.propValue[0].componentData.length,2)
  assert.ok(copiedTab.propValue[0].componentData.every(card=>card.canvasId===`${copiedTab.id}--page`))
  assert.equal(new Set(copiedTab.propValue[0].componentData.map(card=>card.id)).size,2)
  const file=path.resolve(__dirname,'../src/components/visualization/ComponentEditBar.vue')
  const {descriptor,errors}=parse(fs.readFileSync(file,'utf8'),{filename:file})
  assert.deepEqual(errors,[])
  const script=compileScript(descriptor,{id:file})
  const compiled=compileTemplate({source:descriptor.template.content,filename:file,id:file,compilerOptions:{bindingMetadata:script.bindings}})
  assert.deepEqual(compiled.errors,[])
  console.log('Menu source selection, keyboard fallback, Tab placement, recursive copies and component compilation passed.')
}
run().catch(error=>{console.error(error);process.exitCode=1})
