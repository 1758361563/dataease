import Decimal from 'decimal.js'
import { resultKey } from '@/views/chart/components/js/chartFieldInstance'
import { activeSortFields } from '@/views/chart/components/js/chartDisplayLimit'

const GLOBAL_PREFIX = '\uE000DE_GLOBAL_ROW\uE001'

type PivotChart = {
  type?: string
  xAxis?: Axis[]
  xAxisExt?: Axis[]
  yAxis?: Axis[]
  sortPriority?: ChartViewField[]
}

export function pivotSortScope(chart: PivotChart): 'GROUP' | 'GLOBAL' {
  return chart.sortPriority?.some(item => item.scope === 'GLOBAL') ? 'GLOBAL' : 'GROUP'
}

export function globalPivotSortReason(chart: PivotChart): string {
  if (chart.type !== 'table-pivot') return '仅透视表支持跨分组全局排序。'
  if ((chart.xAxis?.length || 0) < 2) return '全局排序至少需要两个数据行维度。'
  if (chart.xAxisExt?.length) return '当前最小实现暂不支持同时配置数据列维度。'
  const first = activeSortFields(chart)[0]
  if (!first || first.axis !== 'yAxis' || !['asc', 'desc'].includes(first.sort)) {
    return '请将一个数值指标设为第一排序项（升序或降序）。'
  }
  if (first.compareCalc?.type && first.compareCalc.type !== 'none') {
    return '快速计算指标会在查询后计算，暂不支持作为全局排序字段。'
  }
  return ''
}

export function isGlobalPivotSort(chart: PivotChart): boolean {
  return pivotSortScope(chart) === 'GLOBAL' && !globalPivotSortReason(chart)
}

function compareValues(a: unknown, b: unknown, numeric: boolean, direction = 'asc') {
  if (a == null || b == null || a === '' || b === '') {
    if ((a == null || a === '') && (b == null || b === '')) return 0
    return a == null || a === '' ? 1 : -1
  }
  let result: number
  if (numeric) {
    try {
      result = new Decimal(String(a)).comparedTo(new Decimal(String(b)))
    } catch (_) {
      result = String(a).localeCompare(String(b))
    }
  } else {
    result = String(a).localeCompare(String(b))
  }
  return direction === 'desc' ? -result : result
}

/**
 * S2 builds a hierarchy from row values and therefore rejoins equal parents.
 * Prefixing each row-level value with its stable global rank keeps every
 * aggregate combination independent; formatters and actions decode it again.
 */
export function prepareGlobalPivotData(chart: PivotChart, source: Record<string, any>[]) {
  if (!isGlobalPivotSort(chart)) return source
  const rowFields = chart.xAxis || []
  const firstSort = activeSortFields(chart)[0]
  const sortField = (chart.yAxis || []).find(
    field =>
      String(field.id) === String(firstSort.id) &&
      (!firstSort.fieldInstanceId || field.fieldInstanceId === firstSort.fieldInstanceId)
  )
  if (!sortField) return source
  const sortKey = resultKey(sortField)
  const indexed = source.map((row, index) => ({ row, index }))
  indexed.sort((left, right) => {
    const primary = compareValues(left.row[sortKey], right.row[sortKey], true, firstSort.sort)
    if (primary) return primary
    for (const field of rowFields) {
      const tied = compareValues(left.row[resultKey(field)], right.row[resultKey(field)], false)
      if (tied) return tied
    }
    return left.index - right.index
  })
  const width = Math.max(8, String(indexed.length).length)
  return indexed.map(({ row }, index) => {
    const rank = String(index).padStart(width, '0')
    const copy = { ...row }
    rowFields.forEach(field => {
      const key = resultKey(field)
      copy[key] = `${GLOBAL_PREFIX}${rank}\uE002${row[key] ?? ''}`
    })
    return copy
  })
}

export function decodeGlobalPivotValue(value: unknown) {
  if (typeof value !== 'string' || !value.startsWith(GLOBAL_PREFIX)) return value
  const valueStart = value.indexOf('\uE002', GLOBAL_PREFIX.length)
  return valueStart < 0 ? value : value.slice(valueStart + 1)
}
