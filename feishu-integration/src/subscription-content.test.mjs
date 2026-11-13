import test from 'node:test'
import assert from 'node:assert/strict'
import { contentDefinition, resolveFilters, buildSubscriptionMessage } from './subscription-content.mjs'

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
test('one post contains all images in selection order', () => {
  const message = buildSubscriptionMessage({ title: '日报', body: '说明', images: [
    { name: '汇总', imageKey: 'a' }, { name: '分品类', imageKey: 'b' }
  ], link: 'https://bi.example.com/' })
  assert.equal(message.zh_cn.title, '日报')
  assert.deepEqual(message.zh_cn.content.flat().filter(item => item.tag === 'img').map(item => item.image_key), ['a', 'b'])
  assert.equal(message.zh_cn.content.at(-1)[0].tag, 'a')
  assert.throws(() => buildSubscriptionMessage({ images: [{ name: '未完成' }] }), /上传/)
  assert.throws(() => buildSubscriptionMessage({ body: '字'.repeat(10000), images: [] }), /过长/)
})
