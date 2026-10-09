import assert from 'node:assert/strict'
import { gridFromValues } from './sheet-snapshot.mjs'
import { columnProfile } from './sheet-onboard.mjs'

const values = [
  ['产品编码', '商品在售状态', '映射编码', null],
  [11030010146, '常规在售', 'YS11030010199', null],
  [11030010199, '常规在售', 'YS11030010199', 'for测试'],
  [null, null, null, null],
  [11020010158, null, 'YS11020010158', null]
]

const suggested = gridFromValues(values, {
  headerRow: 1,
  dataStartRow: 2,
  columnRange: 'A:C'
})
assert.deepEqual(suggested.headers, ['产品编码', '商品在售状态', '映射编码'])
assert.equal(suggested.rows.length, 3)
assert.equal(suggested.columns[0].sqlType, 'DECIMAL(18,4)')
assert.equal(suggested.columns[1].sqlType, 'VARCHAR(255)')

const middle = gridFromValues(values, { headerRow: 1, dataStartRow: 2, columnRange: 'B:C' })
assert.deepEqual(middle.headers, ['商品在售状态', '映射编码'])
assert.equal(middle.rows.length, 3)

const ended = gridFromValues(values, {
  headerRow: 1,
  dataStartRow: 2,
  dataEndRow: 3,
  columnRange: 'A:C'
})
assert.equal(ended.rows.length, 2)

const wide = gridFromValues(
  [
    ['h1', 'h2', 'h3'],
    ['a', 'b', 'c']
  ],
  { columnRange: 'A:M' }
)
assert.deepEqual(wide.headers, ['h1', 'h2', 'h3'])

const profile = columnProfile(values, 1)
assert.equal(profile.map(column => column.letter).join(''), 'ABCD')
assert.equal(profile[3].header, '')
assert.equal(profile[3].nonEmpty, 1)

console.log('sheet-snapshot-range ok')
