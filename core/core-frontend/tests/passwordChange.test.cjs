const assert=require('node:assert/strict'), fs=require('node:fs'), path=require('node:path'), Module=require('node:module'), ts=require('typescript')
const file=path.resolve(__dirname,'../src/views/system/modify-pwd/UpdatePwd.vue')
let outcome, successes=0, logouts=0
const mod=new Module(file)
mod.require=name=>({
  vue:{ref:()=>({value:null}), reactive:x=>x},
  '@/hooks/web/useI18n':{useI18n:()=>({t:x=>x})},
  'lodash-es':{cloneDeep:x=>structuredClone(x)},
  '@/config/axios':{default:{post:async()=>{if(outcome instanceof Error) throw outcome; return outcome}}},
  '@/utils/encryption':{rsaEncryp:x=>x},
  'element-plus-secondary':{ElMessage:{success:()=>successes++}},
  '@/utils/logout':{logoutHandler:()=>logouts++},
  '@/components/custom-password':{}, '@/utils/utils':{isMobile:()=>false}
}[name] || {})
const source=fs.readFileSync(file,'utf8').match(/<script[^>]*>([\s\S]*?)<\/script>/)[1]
global.defineEmits=()=>()=>{}
mod._compile(ts.transpileModule(source+'\nexport {save, updatePwdForm, saving}',{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText,file)
const {save,updatePwdForm,saving}=mod.exports
updatePwdForm.value={validate:callback=>callback(true)}
;(async()=>{
  for(outcome of [undefined,{code:1},new Error('HTTP 404'),new Error('network error')]){
    save(); await new Promise(resolve=>setImmediate(resolve))
    assert.equal(successes,0); assert.equal(logouts,0); assert.equal(saving.value,false)
  }
  outcome={code:0}; save(); await new Promise(resolve=>setImmediate(resolve))
  assert.equal(successes,1); assert.equal(logouts,1); assert.equal(saving.value,false)
  delete global.defineEmits
  console.log('Password change: 404/network/API failures never report success or logout; explicit success does')
})().catch(error=>{console.error(error);process.exitCode=1})
