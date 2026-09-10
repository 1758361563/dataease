/**
 * 观远「基于日期筛选」同环比（DATE_FILTER）
 *
 * 本期 = 仪表板日期筛选器 [start, end]
 * 上期 = 起止端点分别按对比类型做日历映射（非等长互斥周期、非按日拆开后取单期）
 *
 * 月环比/月同比（本项目约定）：
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
  if (!Number.isFinite(Number(startMs)) || !Number.isFinite(Number(endMs))) return null
  if (
    startMs > endMs ||
    !Number.isFinite(new Date(Number(startMs)).valueOf()) ||
    !Number.isFinite(new Date(Number(endMs)).valueOf())
  )
    return null

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

const DATE_BUCKETS = ['filter', 'linkageFilters', 'outerParamsFilters', 'webParamsFilters']

/** 固定日期粒度对应后端 Utils.parseDateTimeValue；拒绝宽松日期溢出和时间宏。 */
function fixedDateRange(value: unknown): [number, number] | null {
  if (typeof value !== 'string') return null
  const match =
    /^(\d{4})(?:[-/](\d{2})(?:[-/](\d{2})(?: (\d{2})(?::(\d{2})(?::(\d{2}))?)?)?)?)?$/.exec(value)
  if (!match) return null
  const [, y, m, d, h, min, sec] = match
  const parts = [+y, +(m || 1), +(d || 1), +(h || 0), +(min || 0), +(sec || 0)]
  const start = Date.UTC(parts[0], parts[1] - 1, parts[2], parts[3], parts[4], parts[5])
  const date = new Date(start)
  if (
    date.getUTCFullYear() !== parts[0] ||
    date.getUTCMonth() + 1 !== parts[1] ||
    date.getUTCDate() !== parts[2] ||
    parts[3] > 23 ||
    parts[4] > 59 ||
    parts[5] > 59
  )
    return null
  const end = sec
    ? start + 1000
    : min
    ? start + 60000
    : h
    ? start + 3600000
    : d
    ? start + 86400000
    : m
    ? Date.UTC(parts[0], parts[1], 1)
    : Date.UTC(parts[0] + 1, 0, 1)
  return [start - 28800000, end - 28800000 - 1]
}

function treeContainsDate(tree: any, fieldId: string): boolean {
  return !!tree?.items?.some(
    (item: any) => fieldIdMatches(item.fieldId, fieldId) || treeContainsDate(item.subTree, fieldId)
  )
}

function collectTreeDates(tree: any, fieldId: string, filters: any[]): boolean {
  if (!treeContainsDate(tree, fieldId)) return true
  if (String(tree.logic).toLowerCase() !== 'and') return false
  for (const item of tree.items) {
    if (item.subTree) {
      if (!collectTreeDates(item.subTree, fieldId, filters)) return false
    } else if (fieldIdMatches(item.fieldId, fieldId)) {
      if (item.filterType !== 'logic' || item.filterTypeTime === 'dynamicDate') return false
      const range = fixedDateRange(item.value)
      if (!range) return false
      const term = item.term
      if (!['eq', 'gt', 'ge', 'lt', 'le'].includes(term)) return false
      filters.push({
        fieldId,
        operator: term === 'eq' ? 'between' : term,
        value: term === 'eq' ? range : [range[term === 'gt' || term === 'le' ? 1 : 0]]
      })
    }
  }
  return true
}

function removeTreeDates(tree: any, fieldId: string): any {
  if (!tree?.items) return tree
  return {
    ...tree,
    items: tree.items.flatMap((item: any) => {
      if (item.subTree) {
        const subTree = removeTreeDates(item.subTree, fieldId)
        return subTree.items.length ? [{ ...item, subTree }] : []
      }
      return fieldIdMatches(item.fieldId, fieldId) ? [] : [item]
    })
  }
}

export type DateComparisonResolution = {
  status: 'ok' | 'missing' | 'empty' | 'unsupported' | 'invalid'
  current?: [number, number]
  previous?: [number, number]
}

export function formatComparisonPeriod(range?: [number, number]): string {
  return (
    range
      ?.map(ms => new Date(ms + 8 * 3600000).toISOString().slice(0, 19).replace('T', ' '))
      .join(' ～ ') || '—'
  )
}

/** 预览和取数共用；无法安全解释的条件不能被忽略。 */
export function resolveDateComparison(
  view: any,
  fieldId: string,
  compareType: string
): DateComparisonResolution {
  if (!fieldId) return { status: 'missing' }
  if (!isGuandataCompareType(compareType)) return { status: 'unsupported' }
  const treeFilters: any[] = []
  if (!collectTreeDates(view?.customFilter, fieldId, treeFilters)) return { status: 'unsupported' }
  let start = -Infinity
  let end = Infinity
  let found = false
  const buckets = [
    ...DATE_BUCKETS.map(bucket => view?.chartExtRequest?.[bucket] || []),
    treeFilters
  ]
  for (const bucket of buckets) {
    for (const filter of bucket) {
      if (filter.filterType === 3 || treeContainsDate(filter.customFilter, fieldId))
        return { status: 'unsupported' }
      if (!fieldIdMatches(filter.fieldId, fieldId)) continue
      found = true
      if (filter.isTree || !Array.isArray(filter.value)) return { status: 'unsupported' }
      const values = filter.value.map((value: unknown) =>
        typeof value === 'number' || (typeof value === 'string' && /^-?\d+$/.test(value))
          ? Number(value)
          : NaN
      )
      if (
        !values.length ||
        values.some(
          (value: number) => !Number.isFinite(value) || !Number.isFinite(new Date(value).valueOf())
        )
      ) {
        return { status: 'invalid' }
      }
      switch (filter.operator) {
        case 'between':
          if (values.length !== 2) return { status: 'invalid' }
          start = Math.max(start, values[0])
          end = Math.min(end, values[1])
          break
        case 'ge':
          start = Math.max(start, values[0])
          break
        case 'gt':
          start = Math.max(start, values[0] + 1)
          break
        case 'le':
          end = Math.min(end, values[0])
          break
        case 'lt':
          end = Math.min(end, values[0] - 1)
          break
        default:
          return { status: 'unsupported' }
      }
    }
  }
  if (!found) return { status: 'missing' }
  if (!Number.isFinite(start) || !Number.isFinite(end)) return { status: 'invalid' }
  if (start > end) return { status: 'empty' }
  const previous = shiftDateRange(start, end, compareType)
  return previous ? { status: 'ok', current: [start, end], previous } : { status: 'invalid' }
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
  const resolved = resolveDateComparison(view, fieldId, compareType)
  if (resolved.status !== 'ok') return null

  const v = JSON.parse(JSON.stringify(view))
  const ext = v.chartExtRequest || {}
  if (treeContainsDate(v.customFilter, fieldId)) {
    v.customFilter = removeTreeDates(v.customFilter, fieldId)
    ext.filter = [
      ...(ext.filter || []),
      { fieldId: String(fieldId), operator: 'between', value: resolved.previous }
    ]
  }
  for (const bucket of DATE_BUCKETS) {
    if (!Array.isArray(ext[bucket])) continue
    ext[bucket] = ext[bucket].flatMap((filter: any) => {
      if (!fieldIdMatches(filter.fieldId, fieldId)) return [filter]
      const remaining = String(filter.fieldId)
        .split(',')
        .map(id => id.trim())
        .filter(id => id !== String(fieldId))
      const shifted = {
        ...filter,
        fieldId: String(fieldId),
        operator: 'between',
        value: resolved.previous
      }
      return remaining.length ? [{ ...filter, fieldId: remaining.join(',') }, shifted] : [shifted]
    })
  }
  v.chartExtRequest = ext
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
 * @returns 比率（percent）、差值（sub）、上期值（pre）；空值和除零返回 null。
 */
export function computeCompareResult(
  current: unknown,
  previous: unknown,
  resultData: string
): number | null {
  if (
    previous === null ||
    previous === undefined ||
    previous === '' ||
    !Number.isFinite(Number(previous))
  ) {
    return null
  }
  const p = Number(previous)
  if (resultData === 'pre') return p
  if (
    current === null ||
    current === undefined ||
    current === '' ||
    !Number.isFinite(Number(current))
  ) {
    return null
  }
  const c = Number(current)
  if (resultData === 'sub') return c - p
  if (p === 0) return null
  return (c - p) / p
}

export function compareGroupKey(quota: any): string {
  const field = quota?.compareCalc?.field || ''
  const type = quota?.compareCalc?.type || ''
  return `${field}||${type}`
}
