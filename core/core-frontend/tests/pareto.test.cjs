const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const ts = require('typescript')
const { parse, compileScript, compileTemplate } = require('@vue/compiler-sfc')
const dir = path.join(__dirname, '../src')
function load(file, imports = {}) {
  const mod = { exports: {} }
  const code = ts.transpileModule(fs.readFileSync(path.join(dir,file),'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText
  new Function('exports','require',code)(mod.exports, name => { if (!(name in imports)) throw Error(name); return imports[name] })
  return mod.exports
}
const base = 'views/chart/components/js/panel/charts/bar/'
const { paretoData } = load(base+'pareto-data.ts')
const input = [{field:'B',value:20,dimensionList:[{id:1,value:'B'}]},{field:'A',value:60},{field:'C',value:20}]
const rows = paretoData(input)
assert.deepEqual(rows.map(x=>x.field), ['A','B','C'])
rows.forEach((row,index)=>assert.ok(Math.abs(row.cumulative-[0.6,0.8,1][index])<1e-12))
assert.equal(input[0].field,'B')
assert.deepEqual(rows[1].dimensionList,input[0].dimensionList)
assert.equal(paretoData([{field:'zero',value:0}])[0].cumulative,null)
assert.equal(paretoData([{field:'big',value:1e308},{field:'big2',value:1e308}])[1].cumulative,1)
assert.throws(()=>paretoData([{field:'bad',value:-1}]))
assert.throws(()=>paretoData([{field:'bad',value:'NaN'}]))
assert.deepEqual(paretoData([]),[])
class Base {
  constructor(name) { this.name=name }
  configTheme(chart,options){return options}
  configLegend(chart,options){return options}
  configXAxis(chart,options){return options}
}
class Plot { constructor(container,options){this.options=options} on(){} }
const { Pareto } = load(base+'pareto.ts', {
  '../../types/impl/g2plot':{G2PlotChartView:Base},
  '../../common/common_antv':{getPadding:()=>0,getYAxis:()=>({}),configPlotTooltipEvent(){}},
  '../../../util':{parseJson:JSON.parse,hexColorToRGBA:x=>x},
  '../../../formatter':{valueFormatter:String},
  './common':{BAR_EDITOR_PROPERTY_INNER:{}}, './pareto-data':{paretoData},
  '../../../tooltipFieldIdentity':load('views/chart/components/js/tooltipFieldIdentity.ts'),
  '@antv/g2plot/esm/plots/dual-axes':{DualAxes:Plot}
})
async function main(){
  const view=new Pareto()
  assert.equal(view.name,'pareto')
  const chart={data:{data:input},yAxis:[{name:'业绩'}],customAttr:JSON.stringify({basicStyle:{}})}
  let plot=await view.drawChart({chart,container:{},action(){}})
  assert.equal(plot.options.yAxis.cumulative.max,1)
  assert.equal(plot.options.annotations.cumulative[0].start[1],0.8)
  assert.deepEqual(plot.options.data[0].map(x=>x.value),[60,20,20])
  assert.equal(plot.options.tooltip.formatter(plot.options.data[1][1]).value,'80.0%')
  chart.customAttr=JSON.stringify({basicStyle:{paretoThresholdShow:false,paretoPercentLabels:true,paretoLineColor:'#123456'}})
  plot=await view.drawChart({chart,container:{},action(){}})
  assert.deepEqual(plot.options.annotations.cumulative,[])
  assert.equal(plot.options.geometryOptions[1].color,'#123456')
  assert.equal(plot.options.geometryOptions[1].label.formatter({cumulative:0.8}),'80.0%')
  for(const file of ['views/chart/components/editor/editor-style/components/BasicStyleSelector.vue','views/chart/components/editor/index.vue','views/chart/components/editor/drag-item/DimensionItem.vue','views/chart/components/editor/drag-item/QuotaItem.vue']) {
    const {descriptor,errors}=parse(fs.readFileSync(path.join(dir,file),'utf8'));assert.deepEqual(errors,[])
    const script=compileScript(descriptor,{id:file})
    const result=compileTemplate({source:descriptor.template.content,filename:file,id:file,compilerOptions:{bindingMetadata:script.bindings,expressionPlugins:['typescript']}})
    assert.deepEqual(result.errors,[])
  }
  console.log('PASS Pareto cumulative percentages, zero/large values, invalid input, linkage data, dual axes, tooltip, saved settings and Vue compilation')
}
main().catch(e=>{console.error(e);process.exitCode=1})
