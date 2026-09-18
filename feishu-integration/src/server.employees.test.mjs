import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from './server.mjs'

describe('employee search endpoints', () => {
  it('rejects missing token and returns filtered matches without mobile', async () => {
    const prev = process.env.FEISHU_INTERNAL_TOKEN
    const { CONFIG } = await import('./config.mjs')
    const prevToken = CONFIG.internalToken
    process.env.FEISHU_INTERNAL_TOKEN = 'test-internal'
    CONFIG.internalToken = 'test-internal'
    const server = createServer({
      searchEmployees: async query => {
        assert.equal(query, '张三')
        return [
          { name: '张三', userId: 'uid_sale', departments: ['销售'], userIdTail: 'sale' },
          { name: '张三', userId: 'uid_fin', departments: ['财务'], userIdTail: '_fin' }
        ]
      },
      resolveEmployee: async userId => {
        assert.equal(userId, 'uid_fin')
        return { name: '张三', userId: 'uid_fin', departments: ['财务'], userIdTail: '_fin' }
      }
    })
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
    const { port } = server.address()
    try {
      const denied = await fetch(`http://127.0.0.1:${port}/v1/employees/search?query=${encodeURIComponent('张三')}`)
      assert.equal(denied.status, 401)
      const ok = await fetch(`http://127.0.0.1:${port}/v1/employees/search?query=${encodeURIComponent('张三')}`, {
        headers: { Authorization: 'Bearer test-internal' }
      })
      assert.equal(ok.status, 200)
      const body = await ok.json()
      assert.equal(body.matches.length, 2)
      assert.equal(body.matches[0].name, '张三')
      assert.equal('mobile' in body.matches[0], false)
      const resolved = await fetch(`http://127.0.0.1:${port}/v1/employees/resolve`, {
        method: 'POST',
        headers: { Authorization: 'Bearer test-internal', 'Content-Type': 'application/json' },
        body: JSON.stringify({ userId: 'uid_fin' })
      })
      assert.equal(resolved.status, 200)
      const employee = await resolved.json()
      assert.equal(employee.userId, 'uid_fin')
      assert.equal(employee.departments[0], '财务')
    } finally {
      await new Promise(resolve => server.close(resolve))
      CONFIG.internalToken = prevToken
      if (prev === undefined) delete process.env.FEISHU_INTERNAL_TOKEN
      else process.env.FEISHU_INTERNAL_TOKEN = prev
    }
  })
})
