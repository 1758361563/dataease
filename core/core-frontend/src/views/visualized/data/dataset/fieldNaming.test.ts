import { test } from 'node:test'
import assert from 'node:assert/strict'
import { fieldHelp, restoreCandidates, planNameRestore } from './fieldNaming.ts'

const fields = [
  {
    id: '9007199254740993',
    name: '销售日期的备注',
    originName: '销售日期',
    description: '销售日期的备注',
    extField: 0
  },
  { id: '2', name: '业务渠道', originName: 'channel', description: '所属渠道', extField: 0 },
  { id: '3', name: '金额', originName: 'SUM(amount)', extField: 2 }
]
test('restore lists physical aliases, marks comment matches, and does not infer computed names', () => {
  const candidates = restoreCandidates(fields)
  assert.equal(candidates.length, 2)
  assert.equal(candidates[0].fromComment, true)
  assert.equal(candidates[1].fromComment, false)
})
test('restoration only changes selected display names and does not mutate fields', () => {
  const before = JSON.stringify(fields)
  const changes = planNameRestore(fields, ['9007199254740993'])
  assert.equal(changes.length, 1)
  assert.equal(changes[0].after, '销售日期')
  assert.equal(JSON.stringify(fields), before)
  assert.equal(fields[1].name, '业务渠道')
  assert.deepEqual(planNameRestore(fields, []), [])
})
test('rejects removed fields and duplicate resulting names', () => {
  assert.throws(() => planNameRestore(fields, ['missing']), /已变化/)
  assert.throws(
    () =>
      planNameRestore(
        [...fields, { id: '4', name: '销售日期', originName: 'another_date', extField: 0 }],
        ['9007199254740993']
      ),
    /重复/
  )
})
test('tooltips label source names and remarks without promoting comments', () => {
  assert.equal(fieldHelp(fields[1]), '业务渠道\n原字段名：channel\n备注：所属渠道')
  assert.equal(fieldHelp(fields[2]), '金额')
})
