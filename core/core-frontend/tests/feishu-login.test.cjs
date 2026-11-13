const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const ts = require('typescript')
const { parse, compileScript, compileTemplate } = require('@vue/compiler-sfc')
const root = path.resolve(__dirname, '../src')
const files = [
  'views/login/FeishuLogin.vue', 'views/login/index.vue',
  'views/company/admin/users/FeishuAccountSelect.vue', 'views/company/admin/users/index.vue',
  'views/company/admin/feishu/index.vue',
  'views/company/integrations/connections/FeishuLoginSettings.vue',
  'views/company/integrations/connections/index.vue'
]
for (const file of files) {
  const { descriptor, errors } = parse(fs.readFileSync(path.join(root, file), 'utf8'), { filename: file })
  assert.deepEqual(errors, [], file)
  const script = compileScript(descriptor, { id: file })
  const template = compileTemplate({ source: descriptor.template.content, filename: file, id: file, compilerOptions: { bindingMetadata: script.bindings } })
  assert.deepEqual(template.errors, [], file)
}

async function main() {
  const file = 'views/login/FeishuLogin.vue'
  const { descriptor } = parse(fs.readFileSync(path.join(root, file), 'utf8'))
  const script = compileScript(descriptor, { id: file })
  const code = ts.transpileModule(script.content, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } }).outputText
  const requests = [], navigation = [], tokens = []
  const storage = new Map()
  global.sessionStorage = { getItem: key => storage.get(key), setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key) }
  const imports = {
    vue: { defineComponent: x => x, ref: value => ({ value }), nextTick: async () => {}, onMounted: () => {}, onBeforeUnmount: () => {} },
    '@/config/axios': { default: { post: async value => { requests.push(value); return { data: { token: 'signed-token', exp: 0 } } } } },
    '@/router': { default: { replace: async value => navigation.push(value) } },
    '@/store/modules/user': { useUserStoreWithOut: () => ({ setToken: value => tokens.push(value), setExp: () => {}, setTime: () => {} }) },
    '@/utils/utils': { cleanPlatformFlag: () => {} }
  }
  const module = { exports: {} }
  new Function('require', 'exports', code)(name => { assert.ok(imports[name], name); return imports[name] }, module.exports)
  const api = module.exports.default.setup({}, { expose: () => {}, emit: () => {} })
  for (const unsafe of ['//evil.example', 'https://evil.example', '/\\evil', '/login', '/ok\nInjected']) assert.equal(api.safePath(unsafe), '/panel/index')
  assert.equal(api.safePath('/preview?id=123'), '/preview?id=123')
  const frameWindow = {}, frame = { contentWindow: frameWindow }
  const qr = { matchOrigin: () => true, matchData: data => data?.source === 'qrcode' && !!data.tmp_code }
  const authorizeUrl = 'https://passport.feishu.cn/suite/passport/oauth/authorize?client_id=cli_test&state=state&redirect_uri=https%3A%2F%2Fbi.example%2Fcallback'
  const event = { origin: 'https://passport.feishu.cn', source: frameWindow, data: { source: 'qrcode', tmp_code: 'code&state=forged' } }
  const redirected = new URL(api.qrRedirect(event, qr, frame, authorizeUrl))
  assert.equal(redirected.searchParams.get('state'), 'state')
  assert.equal(redirected.searchParams.get('tmp_code'), event.data.tmp_code)
  assert.equal(api.qrRedirect({ ...event, origin: 'https://passport.feishu.cn.evil.example' }, qr, frame, authorizeUrl), null)
  assert.equal(api.qrRedirect({ ...event, source: {} }, qr, frame, authorizeUrl), null)
  assert.equal(api.qrRedirect({ ...event, data: 'legacy-string' }, qr, frame, authorizeUrl), null)
  assert.equal(api.qrRedirect({ ...event, data: { source: 'other', tmp_code: 'code' } }, qr, frame, authorizeUrl), null)
  const attempt = { state: 'state', verifier: 'verifier', expiresAt: Date.now() + 10000, redirect: '/preview?id=123' }
  const save = value => storage.set('company.feishu.login', JSON.stringify(value))
  save(attempt)
  await assert.rejects(api.complete('code', 'forged'))
  assert.equal(requests.length, 0)
  save({ ...attempt, expiresAt: 0 })
  await assert.rejects(api.complete('code', 'state'))
  assert.equal(requests.length, 0)
  save(attempt)
  await api.complete('code', 'state')
  assert.deepEqual(requests[0].data, { code: 'code', state: 'state', verifier: 'verifier' })
  assert.deepEqual(tokens, ['signed-token'])
  assert.deepEqual(navigation, [{ path: '/preview?id=123' }])
  assert.equal(storage.size, 0)
  await assert.rejects(api.complete('code', 'state'))
  assert.equal(requests.length, 1)
  console.log('Feishu frontend: 7 SFCs compiled; redirect, browser state, expiry, replay and session completion checks passed')
}
main().catch(error => { console.error(error); process.exitCode = 1 })
