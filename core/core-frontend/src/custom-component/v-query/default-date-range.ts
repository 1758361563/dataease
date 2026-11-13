import dayjs from 'dayjs'
import { getCustomTime } from './time-format'
import { getCustomRange } from './time-format-dayjs'

export interface DateEndpoint {
  mode: 'fixed' | 'relative'
  value?: string
  anchor: 'today' | 'monthStart' | 'monthEnd' | 'yearStart' | 'yearEnd'
  amount: number
  unit: 'year' | 'month' | 'day'
  direction: 'before' | 'after'
  time?: string
}
export interface DefaultRangeConfig {
  rangeEndpoints?: { start: DateEndpoint; end: DateEndpoint }
  relativeToCurrentRange?: string
  timeGranularityMultiple?: string
  [key: string]: any
}

export function createRangeEndpoints(config: DefaultRangeConfig) {
  const make = (end: boolean): DateEndpoint => {
    const suffix = end ? 'Range' : ''
    const unit = config[`relativeToCurrentType${suffix}`]
    return {
      mode: 'relative',
      anchor: 'today',
      amount: Number(config[`timeNum${suffix}`] || 0),
      unit: unit === 'date' ? 'day' : ['year', 'month', 'day'].includes(unit) ? unit : 'day',
      direction: config[`around${suffix}`] === 'b' ? 'after' : 'before',
      time: config[`arbitraryTime${suffix}`]
        ? dayjs(config[`arbitraryTime${suffix}`]).format('HH:mm:ss')
        : end
        ? '23:59:59'
        : '00:00:00'
    }
  }
  return { start: make(false), end: make(true) }
}

export function resolveDefaultRange(
  config: DefaultRangeConfig,
  scene: 'config' | 'panel' = 'panel',
  now = new Date()
): [Date, Date] {
  if (config.relativeToCurrentRange && config.relativeToCurrentRange !== 'custom') {
    return getCustomRange(config.relativeToCurrentRange)
  }
  const endpoints = config.rangeEndpoints
  if (!endpoints) {
    return [
      getCustomTime(
        config.timeNum,
        config.relativeToCurrentType,
        config.timeGranularity,
        config.around,
        config.arbitraryTime,
        config.timeGranularityMultiple,
        `start-${scene}`
      ),
      getCustomTime(
        config.timeNumRange,
        config.relativeToCurrentTypeRange,
        config.timeGranularity,
        config.aroundRange,
        config.arbitraryTimeRange,
        config.timeGranularityMultiple,
        `end-${scene}`
      )
    ]
  }
  const resolve = (endpoint: DateEndpoint, end: boolean) => {
    const invalid = () => new Date(NaN)
    if (!endpoint) return invalid()
    let value = dayjs(now)
    if (endpoint.mode === 'fixed') {
      if (!endpoint.value) return invalid()
      value = dayjs(endpoint.value)
    } else if (endpoint.mode === 'relative') {
      if (
        !Number.isInteger(endpoint.amount) ||
        endpoint.amount < 0 ||
        endpoint.amount > 10000 ||
        !['year', 'month', 'day'].includes(endpoint.unit) ||
        !['before', 'after'].includes(endpoint.direction)
      )
        return invalid()
      switch (endpoint.anchor) {
        case 'today':
          break
        case 'monthStart':
          value = value.startOf('month')
          break
        case 'monthEnd':
          value = value.endOf('month')
          break
        case 'yearStart':
          value = value.startOf('year')
          break
        case 'yearEnd':
          value = value.endOf('year')
          break
        default:
          return invalid()
      }
      value =
        endpoint.direction === 'before'
          ? value.subtract(endpoint.amount, endpoint.unit)
          : value.add(endpoint.amount, endpoint.unit)
      if (config.timeGranularityMultiple === 'datetimerange') {
        if (!/^([01]\d|2[0-3]):[0-5]\d:[0-5]\d$/.test(endpoint.time || '')) return invalid()
        const [hour, minute, second] = endpoint.time.split(':').map(Number)
        value = value.hour(hour).minute(minute).second(second).millisecond(0)
      }
    } else return invalid()
    if (!value.isValid()) return invalid()
    const granularity = config.timeGranularityMultiple
    if (granularity !== 'datetimerange') {
      const unit =
        granularity === 'yearrange' ? 'year' : granularity === 'monthrange' ? 'month' : 'day'
      value = end ? value.endOf(unit).millisecond(0) : value.startOf(unit)
    }
    return value.toDate()
  }
  return [resolve(endpoints.start, false), resolve(endpoints.end, true)]
}
