import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { filterEmployeesByName, publicEmployee } from './contact.mjs'

describe('employee name filter', () => {
  const employees = [
    { name: '张三', userId: 'user_a1', departments: ['销售'] },
    { name: '张三', userId: 'user_a2', departments: ['财务'] },
    { name: '李四', userId: 'user_b1', departments: ['销售'] }
  ]

  it('returns all same-name matches instead of the first one', () => {
    const hits = filterEmployeesByName(employees, '张三')
    assert.equal(hits.length, 2)
    assert.deepEqual(
      hits.map(row => row.userId).sort(),
      ['user_a1', 'user_a2']
    )
    assert.notEqual(hits[0].departments[0], hits[1].departments[0])
  })

  it('does not expose mobile or email on the public record', () => {
    const row = publicEmployee({
      name: '张三',
      userId: 'user_a1',
      departments: ['销售'],
      mobile: '13800000000',
      email: 'a@example.com'
    })
    assert.equal(row.name, '张三')
    assert.equal(row.userId, 'user_a1')
    assert.equal(row.userIdTail, 'r_a1')
    assert.equal('mobile' in row, false)
    assert.equal('email' in row, false)
  })

  it('requires a query', () => {
    assert.deepEqual(filterEmployeesByName(employees, '  '), [])
  })
})
