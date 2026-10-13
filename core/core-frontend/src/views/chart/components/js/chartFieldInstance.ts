/**
 * Chart Field Instance identity helpers.
 * Dataset Field id = 数据字段身份
 * fieldInstanceId = 图表 placement 身份
 * resultKey = 查询结果列身份（派生，不持久化）
 */
export type ChartFieldIdentity = {
  id?: string | number
  dataeaseName?: string
  fieldInstanceId?: string
  metricInstanceId?: string
}

/** Legacy pivot row/column placements can share a dataset key, but not a result column. */
export function ensurePivotDimensionInstances(chart: any) {
  if (chart?.type !== 'table-pivot') return
  const placements = ['xAxis', 'xAxisExt'].flatMap(axis =>
    (chart[axis] || []).map((field, index) => ({ field, axis, index }))
  )
  const counts = new Map<string, number>()
  placements.forEach(({ field }) => {
    const key = resultKey(field)
    if (key) counts.set(key, (counts.get(key) || 0) + 1)
  })
  placements.forEach(({ field, axis, index }) => {
    if ((counts.get(resultKey(field)) || 0) > 1)
      field.fieldInstanceId = `pd_${axis}_${index}_${field.id}`
  })
}

export function formatPivotDate(value: any, field: any) {
  if (value == null || value === '') return value
  if (field?.deType !== 1 || field.dateStyle !== 'M') return value
  const month = Number(value)
  if (!Number.isInteger(month) || month < 1 || month > 12) return value
  return field.dateShowFormat === 'month_number' ? String(month).padStart(2, '0') : `${month}月`
}

export function instanceKey(f?: ChartFieldIdentity | null): string | undefined {
  if (!f) {
    return undefined
  }
  if (f.fieldInstanceId) {
    return f.fieldInstanceId
  }
  if (f.metricInstanceId) {
    return f.metricInstanceId
  }
  return f.id != null ? String(f.id) : undefined
}

export function resultKey(f?: ChartFieldIdentity | null): string | undefined {
  if (!f) {
    return undefined
  }
  if (f.fieldInstanceId) {
    return 'fi_' + f.fieldInstanceId
  }
  if (f.metricInstanceId) {
    return 'mi_' + f.metricInstanceId
  }
  return f.dataeaseName
}

export function matchesSeriesField(
  axis?: ChartFieldIdentity | null,
  fieldKey?: string | null
): boolean {
  if (!axis || fieldKey == null) {
    return false
  }
  return fieldKey === resultKey(axis) || fieldKey === axis.dataeaseName
}

export function rowValue(row?: Record<string, any> | null, field?: ChartFieldIdentity | null): any {
  if (!row || !field) {
    return undefined
  }
  const rk = resultKey(field)
  if (rk != null && row[rk] !== undefined) {
    return row[rk]
  }
  if (field.dataeaseName != null && row[field.dataeaseName] !== undefined) {
    return row[field.dataeaseName]
  }
  return rk != null ? row[rk] : undefined
}

export function resultKeysForFieldId(
  fields: ChartFieldIdentity[] = [],
  fieldId?: string | number,
  fallbackName?: string
): string[] {
  const keys = fields
    .filter(f => fieldId != null && String(f.id) === String(fieldId))
    .map(resultKey)
    .filter(Boolean) as string[]
  if (keys.length) {
    return keys
  }
  return fallbackName ? [fallbackName] : []
}
