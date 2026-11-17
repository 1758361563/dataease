import test from 'node:test'
import assert from 'node:assert/strict'
import vm from 'node:vm'
import { contentDefinition, resolveFilters, buildSubscriptionMessage, previewFailure, requestExecutionPermission, screenshotSegments, dashboardDetailLink } from './subscription-content.mjs'

function permissionProbe(raw, reply, pathname = '/') {
  const requests = []
  const result = vm.runInNewContext(`(${requestExecutionPermission.toString()})('123')`, {
    location: { pathname },
    localStorage: { getItem: key => { requests.push({ key }); return raw } },
    fetch: async (url, options) => {
      requests.push({ url, token: options.headers['X-DE-TOKEN'] })
      const next = Array.isArray(reply) ? reply[requests.filter(r => r.url).length - 1] : reply
      return { ok: next.status === 200, status: next.status, headers: { get: () => next.type || 'application/json' }, json: async () => next.body }
    }
  })
  return { result, requests }
}

test('execution permission decodes the same nested token format as web-storage-cache', async () => {
  const raw = JSON.stringify({ c: Date.now(), e: Date.now() + 60000, v: JSON.stringify('test-session-token') })
  const { result, requests } = permissionProbe(raw, { status: 200, body: { code: 0, data: { allowed: true } } })
  assert.equal((await result).ok, true)
  assert.equal(requests[0].key, 'de_v2_user.token')
  assert.equal(requests[1].token, 'test-session-token')
})

test('execution permission uses Vite /api when /de2api returns the SPA HTML', async () => {
  const { result, requests } = permissionProbe('test', [
    { status: 200, type: 'text/html' },
    { status: 200, body: { code: 0, data: { allowed: true } } }
  ])
  assert.equal((await result).ok, true)
  assert.deepEqual(requests.filter(r => r.url).map(r => r.url), [
    'de2api/company/bi/feishu/content/execution/123',
    'api/company/bi/feishu/content/execution/123'
  ])
})

test('HTML fallback never grants execution and JSON denials do not switch routes', async () => {
  const html = permissionProbe('test', { status: 200, type: 'text/html' })
  assert.equal((await html.result).ok, false)
  assert.match((await html.result).message, /API 代理/)
  const denied = permissionProbe('test', { status: 403, body: { code: 403, msg: '无权限' } })
  assert.equal((await denied.result).ok, false)
  assert.equal(denied.requests.filter(r => r.url).length, 1)
})

test('execution permission handles app prefixes and legacy plain tokens', async () => {
  for (const raw of ['test-session-token', JSON.stringify({ v: 'test-session-token' })]) {
    const { result, requests } = permissionProbe(raw, { status: 200, body: { code: 0, data: { allowed: true } } }, '/bi/')
    assert.equal((await result).ok, true)
    assert.equal(requests[0].key, 'bi_user.token')
    assert.equal(requests[1].token, 'test-session-token')
  }
})

test('execution permission distinguishes missing sessions, missing endpoints and actual denials', async () => {
  const missing = permissionProbe(null, {})
  assert.equal((await missing.result).code, 401)
  assert.equal(missing.requests.length, 1)
  const absent = await permissionProbe('test', { status: 404, body: {} }).result
  assert.match(absent.message, /接口不存在/)
  const denied = await permissionProbe('test', { status: 200, body: { code: 403, msg: '无导出权限' } }).result
  assert.equal(denied.ok, false)
  assert.equal(denied.message, '无导出权限')
  const unallowed = await permissionProbe('test', { status: 200, body: { code: 0, data: { allowed: false } } }).result
  assert.equal(unallowed.ok, false)
})

test('legacy subscriptions retain their single card', () => {
  assert.deepEqual(contentDefinition({ chart_id: '123' }), { version: 1, mode: 'CARDS', chartIds: ['123'] })
  assert.throws(() => contentDefinition({}), /卡片标识/)
})
test('card order is preserved and invalid selections are rejected', () => {
  assert.deepEqual(contentDefinition({ content: { mode: 'CARDS', chartIds: ['3', '1'] } }).chartIds, ['3', '1'])
  for (const ids of [[], ['1', 1], ['bad'], Array.from({ length: 13 }, (_, i) => String(i))])
    assert.throws(() => contentDefinition({ content: { mode: 'CARDS', chartIds: ids } }))
  assert.equal(contentDefinition({ content: { mode: 'DASHBOARD' } }).mode, 'DASHBOARD')
})
test('fixed filters retain date ranges, multi-select arrays and numeric zero', () => {
  const items = [{ id: '1', name: '日期', value: ['2026-11-01', '2026-11-12'], dateRule: 'fixed' },
    { id: '2', name: '门店', value: ['A', 'B'] }, { name: '数量', value: 0 }]
  assert.deepEqual(resolveFilters(items).map(f => f.value), [items[0].value, ['A', 'B'], 0])
  assert.throws(() => resolveFilters([null]))
})
test('relative dates use the scheduled instant in Shanghai including month rollover', () => {
  const field = rule => [{ name: '日期', dateRule: rule }]
  const midnight = Date.parse('2026-11-01T00:00:00+08:00')
  assert.deepEqual(resolveFilters(field('yesterday'), midnight)[0].value, ['2026-10-31', '2026-10-31'])
  assert.deepEqual(resolveFilters(field('last7'), String(midnight))[0].value, ['2026-10-25', '2026-10-31'])
  assert.deepEqual(resolveFilters(field('monthToYesterday'), midnight)[0].value, ['2026-10-01', '2026-10-31'])
  assert.throws(() => resolveFilters(field('unknown'), midnight))
})
test('one interactive card contains all images in selection order', () => {
  const message = buildSubscriptionMessage({ title: '日报', body: '说明', images: [
    { name: '汇总', imageKey: 'a' }, { name: '分品类', imageKey: 'b' }
  ], link: 'https://bi.example.com/' })
  assert.equal(message.header.title.content, '日报')
  assert.deepEqual(message.elements.filter(item => item.tag === 'img').map(item => item.img_key), ['a', 'b'])
  assert.equal(message.elements.at(-1).actions[0].url, 'https://bi.example.com/')
  assert.throws(() => buildSubscriptionMessage({ images: [{ name: '未完成' }] }), /上传/)
  assert.throws(() => buildSubscriptionMessage({ body: '字'.repeat(10000), images: [] }), /过长/)
})

test('long screenshot segments cover the full image without gaps or overlaps', () => {
  assert.deepEqual(screenshotSegments(4501), [{ y: 0, height: 2000 }, { y: 2000, height: 2000 }, { y: 4000, height: 501 }])
  assert.deepEqual(screenshotSegments(2000), [{ y: 0, height: 2000 }])
  assert.throws(() => screenshotSegments(0))
  assert.throws(() => screenshotSegments(16001))
})

test('preview errors explain the stage without exposing raw diagnostics', () => {
  assert.match(previewFailure('打开仪表板', new Error('net::ERR_CONNECTION_REFUSED')), /打开仪表板.*无法访问 BI/)
  assert.match(previewFailure('生成图片', Object.assign(new Error('Timeout 30000ms exceeded'), { name: 'TimeoutError' })), /生成图片.*等待超时/)
  assert.match(previewFailure('打开所选卡片', new Error('无法打开选中的卡片，请检查卡片是否仍在仪表板中')), /卡片是否仍在/)
  assert.doesNotMatch(previewFailure('准备预览', new Error('password=private-secret')), /private-secret/)
})

 test('dashboard links preserve base path and long IDs without internal screenshot URLs', () => {
 const id = '7528294096973430762'
 assert.equal(dashboardDetailLink('https://bi.example.com/', id), 'https://bi.example.com/#/panel/index?dvId=' + id)
 assert.equal(dashboardDetailLink('https://bi.example.com/bi/?old=1#/login', id), 'https://bi.example.com/bi/#/panel/index?dvId=' + id)
 assert.equal(dashboardDetailLink('', id), null)
 assert.throws(() => dashboardDetailLink('javascript:alert(1)', id))
 const card=buildSubscriptionMessage({ images: [], link: dashboardDetailLink('https://bi.example.com', id) })
 assert.equal(card.elements.at(-1).actions[0].text.content, '查看详情')
 })
