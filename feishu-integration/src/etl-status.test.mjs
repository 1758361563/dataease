import test from 'node:test'
import assert from 'node:assert/strict'
import { readEtlRunLog } from './etl-status.mjs'

test('freshness reads formal refresh records even when a newer test run exists', async () => {
  const fixture = [
    { id: 2, etl_name: '销售订单数据-线下', kind: 'refresh_test', status: 'success' },
    { id: 1, etl_name: '销售订单数据-线下', kind: 'refresh', status: 'fail' }
  ]
  const result = await readEtlRunLog(['销售订单数据-线下', '销售订单数据-线下'], async (sql, args) => {
    assert.match(sql, /AND kind = 'refresh'/)
    assert.deepEqual(args, ['销售订单数据-线下'])
    return fixture.filter(row => row.kind === 'refresh')
  })
  assert.deepEqual(result.records.map(row => row.id), [1])
  assert.equal(result.records[0].status, 'fail')
})
test('empty tasks do not query the database', async () => {
  const result = await readEtlRunLog([], () => { throw new Error('unexpected query') })
  assert.deepEqual(result, { available: true, records: [] })
})
