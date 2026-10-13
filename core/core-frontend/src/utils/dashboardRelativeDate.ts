import type { DashboardRelativeDate } from '@/models/dashboardCalculation'

/** Preview only; persisted defaults are evaluated by the backend with the dashboard timezone. */
export const previewRelativeDate = (
  rule: DashboardRelativeDate,
  timezone: string,
  reference = new Date()
): string => {
  if (!Number.isInteger(rule.offset) || Math.abs(rule.offset) > 1200)
    throw new Error('偏移必须是 -1200 到 1200 的整数')
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: timezone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit'
  }).formatToParts(reference)
  const value = (name: string) => Number(parts.find(part => part.type === name)?.value)
  const date = new Date(Date.UTC(value('year'), value('month') - 1, value('day')))
  if (rule.base === 'YESTERDAY') date.setUTCDate(date.getUTCDate() - 1)
  else if (rule.base !== 'TODAY') throw new Error('不支持的日期基准')
  switch (rule.period) {
    case 'DAY':
      date.setUTCDate(date.getUTCDate() + rule.offset)
      break
    case 'WEEK':
      date.setUTCDate(date.getUTCDate() - ((date.getUTCDay() + 6) % 7) + rule.offset * 7)
      break
    case 'MONTH':
      date.setUTCDate(1)
      date.setUTCMonth(date.getUTCMonth() + rule.offset)
      break
    case 'QUARTER':
      date.setUTCDate(1)
      date.setUTCMonth(Math.floor(date.getUTCMonth() / 3) * 3 + rule.offset * 3)
      break
    case 'YEAR':
      date.setUTCMonth(0, 1)
      date.setUTCFullYear(date.getUTCFullYear() + rule.offset)
      break
    default:
      throw new Error('不支持的日期周期')
  }
  if (rule.position === 'END') {
    switch (rule.period) {
      case 'DAY':
        break
      case 'WEEK':
        date.setUTCDate(date.getUTCDate() + 6)
        break
      case 'MONTH':
        date.setUTCMonth(date.getUTCMonth() + 1, 0)
        break
      case 'QUARTER':
        date.setUTCMonth(date.getUTCMonth() + 3, 0)
        break
      case 'YEAR':
        date.setUTCFullYear(date.getUTCFullYear() + 1, 0, 0)
        break
    }
  } else if (rule.position !== 'START') throw new Error('不支持的日期位置')
  return date.toISOString().slice(0, 10)
}
