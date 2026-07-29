/**
 * 观远日历端点映射自测（无 vitest；node 直接跑）
 * 运行：node guandataCalendarShift.selftest.mjs
 *
 * 与 guandataCalendarShift.ts 保持同算法；改 ts 时请同步本文件并重跑。
 */
function daysInMonth(year, month) {
  return new Date(year, month, 0).getDate()
}

function shiftYearMonth(year, month, deltaMonths) {
  const idx = year * 12 + (month - 1) + deltaMonths
  const y = Math.floor(idx / 12)
  const m = idx - y * 12 + 1
  return { year: y, month: m }
}

function subtractCalendarMonths(input, amount) {
  const { year, month } = shiftYearMonth(input.year, input.month, -amount)
  const last = daysInMonth(year, month)
  return { year, month, day: Math.min(input.day, last) }
}

function subtractCalendarYears(input, amount) {
  const year = input.year - amount
  const last = daysInMonth(year, input.month)
  return { year, month: input.month, day: Math.min(input.day, last) }
}

function format(d) {
  return `${d.year}-${String(d.month).padStart(2, '0')}-${String(d.day).padStart(2, '0')}`
}

function parse(iso) {
  const [y, m, d] = iso.split('-').map(Number)
  return { year: y, month: m, day: d }
}

function assertEq(actual, expected, msg) {
  if (actual !== expected) {
    throw new Error(`${msg}\n  expected: ${expected}\n  actual:   ${actual}`)
  }
}

function shiftRange(startIso, endIso, kind) {
  const shift = d =>
    kind === 'year' ? subtractCalendarYears(d, 1) : subtractCalendarMonths(d, 1)
  return `${format(shift(parse(startIso)))} ～ ${format(shift(parse(endIso)))}`
}

function shiftOne(iso, kind) {
  const d = parse(iso)
  return format(kind === 'year' ? subtractCalendarYears(d, 1) : subtractCalendarMonths(d, 1))
}

// —— 售后确认用例 ——
assertEq(
  shiftRange('2026-07-01', '2026-07-28', 'month'),
  '2026-06-01 ～ 2026-06-28',
  '普通同日号'
)
assertEq(
  shiftRange('2026-06-28', '2026-07-02', 'month'),
  '2026-05-28 ～ 2026-06-02',
  '跨月区间'
)
assertEq(shiftOne('2026-03-30', 'month'), '2026-02-28', '30 日→平年二月')
assertEq(shiftOne('2026-03-31', 'month'), '2026-02-28', '31 日→平年二月')
assertEq(shiftOne('2024-03-31', 'month'), '2024-02-29', '31 日→闰年二月')
assertEq(
  shiftRange('2026-03-31', '2026-04-30', 'month'),
  '2026-02-28 ～ 2026-03-30',
  '两端分别截断（非月末对齐成 2/28～3/31）'
)
assertEq(
  shiftRange('2026-03-01', '2026-03-31', 'month'),
  '2026-02-01 ～ 2026-02-28',
  '完整三月→碰巧完整二月（非智能识别自然月）'
)
assertEq(shiftOne('2024-02-29', 'year'), '2023-02-28', '闰日年同比')
assertEq(
  shiftRange('2024-02-29', '2024-03-31', 'year'),
  '2023-02-28 ～ 2023-03-31',
  '年同比区间'
)

// 跨年月份
assertEq(shiftOne('2026-01-15', 'month'), '2025-12-15', '跨年减月')

// 映射后天数可不同
{
  const curDays = 31 // 3/1～3/31
  const cmpStart = parse('2026-02-01')
  const cmpEnd = parse('2026-02-28')
  const cmpDays =
    (Date.UTC(cmpEnd.year, cmpEnd.month - 1, cmpEnd.day) -
      Date.UTC(cmpStart.year, cmpStart.month - 1, cmpStart.day)) /
      86400000 +
    1
  assertEq(curDays === cmpDays, false, '不保天数相同')
  assertEq(cmpDays, 28, '对比期天数')
}

// 连续多次减月
{
  let d = parse('2026-03-31')
  d = subtractCalendarMonths(d, 1) // 02-28
  d = subtractCalendarMonths(d, 1) // 01-28
  assertEq(format(d), '2026-01-28', '连续两次减月')
}

// 禁止依赖的溢出陷阱对照：原生 setMonth 在部分环境会溢出，我们的算法不得如此
assertEq(shiftOne('2026-03-31', 'month'), '2026-02-28', '不得溢出成 3 月')

console.log('guandataCalendarShift.selftest: all passed')
