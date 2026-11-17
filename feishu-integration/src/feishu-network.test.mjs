import { test } from 'node:test'
import assert from 'node:assert/strict'
import { feishuErrorMessage, readFeishuJson } from './feishu.mjs'

test('DOM timeout code 23 is not a Feishu business error', () => {
  const err = new DOMException('The operation was aborted due to timeout', 'TimeoutError')
  assert.equal(err.code, 23)
  err.feishuOperation = '读取飞书部门列表'
  assert.match(feishuErrorMessage(err), /读取飞书部门列表超时/)
  assert.doesNotMatch(feishuErrorMessage(err), /错误码 23/)
  assert.equal(feishuErrorMessage({ http: 400, code: 23 }), '飞书接口调用失败，错误码 23')
  assert.equal(feishuErrorMessage({ code: 23 }), null)
})

test('read request retries one transient failure and preserves successful JSON', async t => {
  let calls = 0
  t.mock.method(globalThis, 'fetch', async () => {
    if (++calls === 1) throw new DOMException('timeout', 'TimeoutError')
    return new Response(JSON.stringify({ code: 0, data: { items: [] } }))
  })
  const result = await readFeishuJson('/contact/v3/departments/0/children', {}, '读取飞书部门列表')
  assert.equal(calls, 2)
  assert.equal(result.json.code, 0)
})

test('persistent timeout stops after two attempts and identifies the failing operation', async t => {
  let calls = 0
  t.mock.method(globalThis, 'fetch', async () => {
    calls++
    throw new DOMException('timeout', 'TimeoutError')
  })
  await assert.rejects(readFeishuJson('/contact/v3/users/find_by_department', {}, '读取飞书部门成员'), err => {
    assert.match(feishuErrorMessage(err), /读取飞书部门成员超时/)
    return true
  })
  assert.equal(calls, 2)
})

test('permission responses are not retried', async t => {
  let calls = 0
  t.mock.method(globalThis, 'fetch', async () => {
    calls++
    return new Response(JSON.stringify({ code: 99991672, msg: 'Access denied' }), { status: 403 })
  })
  const result = await readFeishuJson('/contact/v3/users/find_by_department', {}, '读取飞书部门成员')
  assert.equal(result.http, 403)
  assert.equal(result.json.code, 99991672)
  assert.equal(calls, 1)
})
