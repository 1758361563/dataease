import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { matchesField, pasteValues, validateRules, fieldOriginalName } from './previewState.ts'

describe('dataset preview state', () => {
  it('shows an original name only when a display alias differs', () => {
    assert.equal(fieldOriginalName({ name: 'taste_name', originName: 'taste_name' }), '')
    assert.equal(fieldOriginalName({ originName: 'taste_name' }), '')
    assert.equal(fieldOriginalName({ name: '香型', originName: 'taste_name' }), 'taste_name')
  })
  it('searches display and original names without changing field metadata', () => {
    const field = { id: '9007199254740993', name: '门店', originName: 'Store_Name', deType: 0 }
    const before = JSON.stringify(field)
    assert(matchesField(field, ' 门店 '))
    assert(matchesField(field, 'STORE'))
    assert(!matchesField(field, '金额'))
    assert.equal(JSON.stringify(field), before)
  })
  it('preserves punctuation and leading spaces in pasted exact-match values', () => {
    assert.deepEqual(pasteValues("a,b\r\na,b\r\n O'Reilly\n"), ['a,b', " O'Reilly"])
  })
  it('rejects stale references and reversed numeric ranges', () => {
    const fields = [{ id: '1', name: '金额', deType: 3 }]
    assert(validateRules([{ fieldId: '2', operator: 'eq', values: ['1'] }], fields))
    assert(validateRules([{ fieldId: '1', operator: 'between', values: ['10', '2'] }], fields))
    assert(validateRules([{ fieldId: '1', operator: 'eq', values: ['abc'] }], fields))
    assert.equal(
      validateRules([{ fieldId: '1', operator: 'between', values: ['2', '10'] }], fields),
      ''
    )
    assert.equal(validateRules([{ fieldId: '1', operator: 'null', values: [] }], fields), '')
    assert(
      validateRules([{ fieldId: '1', operator: 'between', values: ['2', null as any] }], fields)
    )
  })
})
