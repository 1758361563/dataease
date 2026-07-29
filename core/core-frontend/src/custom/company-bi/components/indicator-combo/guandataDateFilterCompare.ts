/**
 * 观远「基于日期筛选」同环比（DATE_FILTER）
 *
 * 本期 = 仪表板日期筛选器 [start, end]
 * 上期 = 起止端点分别按对比类型做日历映射（非等长互斥周期、非按日拆开后取单期）
 *
 * 月环比/月同比（售后确认）：
 *   compareStart/End = subtractCalendarMonths(current*, 1)
 *   目标月无对应日号 → 截到该月最后一天；无额外月末对齐；不保天数相同
 *
 * 年同比：两端点分别减 1 自然年，闰日截断同理
 *
 * 增长率：(本期 - 上期) / 上期；先整段聚合再算，不对每日增长率再汇总
 */
import {
  applyDateOnlyKeepingTime,
  shiftDateOnlyByCompareType,
  toDateOnlyFromMs
} from './guandataCalendarShift'

export type GuandataCompareType =
  | 'day_mom'
  | 'week_yoy'
  | 'week_mom'
  | 'month_yoy'
  | 'month_mom'
  | 'year_yoy'
  | 'year_mom'

export function isGuandataCompareType(type: string): boolean {
  return !!shiftDateOnlyByCompareType({ year: 2020, month: 1, day: 1 }, type)
}

/** 起止端点分别映射，保留原时间戳的时分秒（通常为日始/日末） */
export function shiftDateRange(
  startMs: number,
  endMs: number,
  compareType: string
): [number, number] | null {
  if (startMs == null || endMs == null) return null
  if (Number.isNaN(Number(startMs)) || Number.isNaN(Number(endMs))) return null

  const shiftOne = (ms: number): number | null => {
    const next = shiftDateOnlyByCompareType(toDateOnlyFromMs(Number(ms)), compareType)
    if (!next) return null
    return applyDateOnlyKeepingTime(Number(ms), next)
  }

  const a = shiftOne(Number(startMs))
  const b = shiftOne(Number(endMs))
  if (a == null || b == null) return null
  return [a, b]
}

function fieldIdMatches(filterFieldId: unknown, targetFieldId: string): boolean {
  if (!targetFieldId) return false
  return String(filterFieldId ?? '')
    .split(',')
    .map(s => s.trim())
    .includes(String(targetFieldId))
}

function isBetweenDateFilter(f: any): boolean {
  return f?.operator === 'between' && Array.isArray(f.value) && f.value.length >= 2
}

/** 在 chartExtRequest 各 filter 桶中找与同环比日期字段一致的 between 条件 */
export function findDateBetweenFilter(extRequest: any, fieldId: string): any | null {
  if (!extRequest || !fieldId) return null
  const buckets = [
    extRequest.filter,
    extRequest.linkageFilters,
    extRequest.outerParamsFilters,
    extRequest.webParamsFilters
  ]
  for (const list of buckets) {
    if (!Array.isArray(list)) continue
    for (const f of list) {
      if (isBetweenDateFilter(f) && fieldIdMatches(f.fieldId, fieldId)) {
        return f
      }
    }
  }
  return null
}

function mapFilterBucket(list: any[] | undefined, fieldId: string, range: [number, number]) {
  if (!Array.isArray(list)) return list
  return list.map(f => {
    if (!isBetweenDateFilter(f) || !fieldIdMatches(f.fieldId, fieldId)) return f
    return { ...f, value: [range[0], range[1]] }
  })
}

/**
 * 复制 view，将匹配日期字段的筛选区间整体偏移为上期。
 * 找不到外部日期筛选时返回 null（观远编辑态「无限～无限」等价：无法算对比）。
 */
export function cloneViewWithShiftedDateFilter(
  view: any,
  fieldId: string,
  compareType: string
): any | null {
  const found = findDateBetweenFilter(view?.chartExtRequest, fieldId)
  if (!found) return null
  const shifted = shiftDateRange(Number(found.value[0]), Number(found.value[1]), compareType)
  if (!shifted) return null

  const v = JSON.parse(JSON.stringify(view))
  const ext = v.chartExtRequest || {}
  v.chartExtRequest = {
    ...ext,
    filter: mapFilterBucket(ext.filter, fieldId, shifted),
    linkageFilters: mapFilterBucket(ext.linkageFilters, fieldId, shifted),
    outerParamsFilters: mapFilterBucket(ext.outerParamsFilters, fieldId, shifted),
    webParamsFilters: mapFilterBucket(ext.webParamsFilters, fieldId, shifted)
  }
  return v
}

export function stripCompareCalc(quota: any) {
  const q = JSON.parse(JSON.stringify(quota || {}))
  if (q.compareCalc) {
    q.compareCalc = { ...q.compareCalc, type: 'none' }
  }
  return q
}

/**
 * @returns 比率（percent）、差值（sub）、上期值（pre）；无法计算返回 null；除零增长率返回 Infinity
 */
export function computeCompareResult(
  current: unknown,
  previous: unknown,
  resultData: string
): number | null {
  if (current === null || current === undefined || current === '' || isNaN(Number(current))) {
    return null
  }
  if (previous === null || previous === undefined || previous === '' || isNaN(Number(previous))) {
    return null
  }
  const c = Number(current)
  const p = Number(previous)
  if (resultData === 'pre') return p
  if (resultData === 'sub') return c - p
  if (p === 0) return c === 0 ? 0 : Number.POSITIVE_INFINITY
  return (c - p) / p
}

export function compareGroupKey(quota: any): string {
  const field = quota?.compareCalc?.field || ''
  const type = quota?.compareCalc?.type || ''
  return `${field}||${type}`
}
