import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { evaluateFreshness, parseFreshnessBinding, UNCHECKED_LABEL } from './etl-status.mjs'
import { currentDueSlot, parseSchedule, shanghaiWallToMs } from './schedule.mjs'

describe('Phase 2E schedule slots', () => {
  it('parses daily/weekly/monthly with Asia/Shanghai', () => {
    const daily = parseSchedule({ kind: 'daily', autoRun: true, time: '09:00', timezone: 'Asia/Shanghai' })
    assert.equal(daily.kind, 'daily')
    assert.equal(daily.autoRun, true)
    assert.equal(daily.timezone, 'Asia/Shanghai')
    const weekly = parseSchedule('{"kind":"weekly","autoRun":true,"time":"08:30","dayOfWeek":5}')
    assert.equal(weekly.dayOfWeek, 5)
    assert.equal(weekly.hour, 8)
    assert.equal(weekly.minute, 30)
  })

  it('fires the same Shanghai minute only after the slot starts', () => {
    const slot = shanghaiWallToMs(2026, 9, 17, 9, 0)
    const schedule = { kind: 'daily', autoRun: true, time: '09:00', timezone: 'Asia/Shanghai' }
    assert.equal(currentDueSlot(slot - 1, schedule), null)
    assert.equal(currentDueSlot(slot, schedule), slot)
    assert.equal(currentDueSlot(slot + 60_000, schedule), slot)
  })

  it('weekly and monthly require matching Shanghai calendar fields', () => {
    const thursday0900 = shanghaiWallToMs(2026, 9, 17, 9, 0)
    const weekly = { kind: 'weekly', autoRun: true, time: '09:00', dayOfWeek: 4, timezone: 'Asia/Shanghai' }
    assert.equal(currentDueSlot(thursday0900, weekly), thursday0900)
    const friday = { kind: 'weekly', autoRun: true, time: '09:00', dayOfWeek: 5, timezone: 'Asia/Shanghai' }
    assert.equal(currentDueSlot(thursday0900, friday), null)
    const monthly = { kind: 'monthly', autoRun: true, time: '09:00', dayOfMonth: 17, timezone: 'Asia/Shanghai' }
    assert.equal(currentDueSlot(thursday0900, monthly), thursday0900)
    const otherMonthDay = { kind: 'monthly', autoRun: true, time: '09:00', dayOfMonth: 1, timezone: 'Asia/Shanghai' }
    assert.equal(currentDueSlot(thursday0900, otherMonthDay), null)
  })

  it('manual or autoRun=false never produces a due slot', () => {
    const now = shanghaiWallToMs(2026, 9, 17, 10, 0)
    assert.equal(currentDueSlot(now, { kind: 'daily', autoRun: false, time: '09:00' }), null)
    assert.equal(currentDueSlot(now, { kind: 'manual', autoRun: true, time: '09:00' }), null)
  })

  it('does not pretend an unbound subscription was freshness-checked', () => {
    const empty = evaluateFreshness(parseFreshnessBinding(null), { available: false, records: [] })
    assert.equal(empty.checked, false)
    assert.equal(empty.blocked, false)
    assert.equal(empty.label, UNCHECKED_LABEL)
    const flagOnly = evaluateFreshness(parseFreshnessBinding('{"requireEtl":true}'), { available: false, records: [] })
    assert.equal(flagOnly.checked, false)
    assert.equal(flagOnly.blocked, false)
    assert.equal(flagOnly.label, UNCHECKED_LABEL)
  })
})
