/**
 * 日期筛选对比的日历端点映射（本项目约定）
 *
 * 月环比 / 月同比：两端点分别减 N 个自然月
 * 年同比 / 年环比：两端点分别减 N 个自然年
 *
 * 规则：
 * - 目标月存在原日号 → 保留
 * - 不存在 → 截到目标月最后一天
 * - 无额外「月末对齐」；不保天数相同；不识别完整自然月
 * - 禁止依赖 Date#setMonth 溢出行为
 *
 * 日/周：两端点分别减固定日历日数（环比 −1 天，周同比 −7 天）
 */

export type DateOnly = {
  year: number
  /** 1–12 */
  month: number
  day: number
}

export function daysInMonth(year: number, month: number): number {
  // Date 月份 0-based；day=0 → 上月最后一天
  return new Date(Date.UTC(year, month, 0)).getUTCDate()
}

export function shiftYearMonth(
  year: number,
  month: number,
  deltaMonths: number
): { year: number; month: number } {
  const idx = year * 12 + (month - 1) + deltaMonths
  const y = Math.floor(idx / 12)
  const m = idx - y * 12 + 1
  return { year: y, month: m }
}

/** 减自然月；无对应日号则截到目标月最后一天 */
export function subtractCalendarMonths(input: DateOnly, amount: number): DateOnly {
  const { year, month } = shiftYearMonth(input.year, input.month, -amount)
  const last = daysInMonth(year, month)
  return { year, month, day: Math.min(input.day, last) }
}

/** 减自然年；闰日等到非闰年截到 2/28 */
export function subtractCalendarYears(input: DateOnly, amount: number): DateOnly {
  const year = input.year - amount
  const last = daysInMonth(year, input.month)
  return { year, month: input.month, day: Math.min(input.day, last) }
}

export function subtractCalendarDays(input: DateOnly, amount: number): DateOnly {
  const d = new Date(Date.UTC(input.year, input.month - 1, input.day - amount))
  return {
    year: d.getUTCFullYear(),
    month: d.getUTCMonth() + 1,
    day: d.getUTCDate()
  }
}

export function formatDateOnly(d: DateOnly): string {
  const m = String(d.month).padStart(2, '0')
  const day = String(d.day).padStart(2, '0')
  return `${d.year}-${m}-${day}`
}

export function parseDateOnly(iso: string): DateOnly {
  const [y, m, d] = iso.split('-').map(Number)
  return { year: y, month: m, day: d }
}

// 当前业务日期固定为北京时间 UTC+08:00，不随浏览器时区变化。
const BUSINESS_OFFSET_MS = 8 * 60 * 60 * 1000

/** 从北京时间时间戳取纯日期。 */
export function toDateOnlyFromMs(ms: number): DateOnly {
  const d = new Date(ms + BUSINESS_OFFSET_MS)
  return {
    year: d.getUTCFullYear(),
    month: d.getUTCMonth() + 1,
    day: d.getUTCDate()
  }
}

/** 用新 Y-M-D 重建时间戳，保留原时分秒毫秒（避免 setMonth 溢出） */
export function applyDateOnlyKeepingTime(ms: number, date: DateOnly): number {
  const src = new Date(ms + BUSINESS_OFFSET_MS)
  return (
    Date.UTC(
      date.year,
      date.month - 1,
      date.day,
      src.getUTCHours(),
      src.getUTCMinutes(),
      src.getUTCSeconds(),
      src.getUTCMilliseconds()
    ) - BUSINESS_OFFSET_MS
  )
}

/**
 * 按观远对比类型映射单个端点的纯日期。
 * month_mom / month_yoy 同一套自然月端点映射；year_* 同一套自然年映射。
 */
export function shiftDateOnlyByCompareType(input: DateOnly, compareType: string): DateOnly | null {
  switch (compareType) {
    case 'day_mom':
      return subtractCalendarDays(input, 1)
    case 'week_yoy':
    case 'week_mom':
      return subtractCalendarDays(input, 7)
    case 'month_mom':
    case 'month_yoy':
      return subtractCalendarMonths(input, 1)
    case 'year_mom':
    case 'year_yoy':
      return subtractCalendarYears(input, 1)
    default:
      return null
  }
}
