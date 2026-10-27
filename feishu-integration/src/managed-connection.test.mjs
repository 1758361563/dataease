import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { decryptConnection } from './managed-connection.mjs'

test('reads AES-GCM connection secrets with provider/key binding shared with Java', () => {
  const key = createHash('sha256').update('companybi/integration/v1\nunit-test-master').digest('base64')
  const fixture = 'v1.BwcHBwcHBwcHBwcH.KzjDT5vnK258E+wnnjt+MKZl8mcOrMyGr42JE9Sq32J58IChT8bCUdU='
  assert.deepEqual(decryptConnection(fixture, key), { appSecret: 'test-only' })
  assert.throws(() => decryptConnection(fixture, key, 'OTHER'))
  assert.throws(() => decryptConnection(fixture, key, 'FEISHU', 'other'))
  assert.throws(() => decryptConnection(fixture.slice(0, -5), key))
})
