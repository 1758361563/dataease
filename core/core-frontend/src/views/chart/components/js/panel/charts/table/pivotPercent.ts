import Decimal from 'decimal.js'
export type PivotPercentCache = Map<string, Map<string, Decimal>>

export function isScopedPercent(field: any) {
  return (
    field?.compareCalc?.type === 'percent' &&
    field.compareCalc.percentScope &&
    field.compareCalc.percentScope !== 'table'
  )
}

/** Ratios for totals use original values; missing query dimensions denote rolled-up groups. */
export function pivotPercentValue(
  query: Record<string, any>,
  basis: any[],
  rowKeys: string[],
  colKeys: string[],
  field: any,
  cache: PivotPercentCache = new Map()
) {
  const calc = field.compareCalc
  const measure =
    field.resultKey ||
    (field.fieldInstanceId
      ? `fi_${field.fieldInstanceId}`
      : field.metricInstanceId
      ? `mi_${field.metricInstanceId}`
      : field.dataeaseName)
  let retained: string[]
  switch (calc.percentScope) {
    case 'row':
      retained = rowKeys
      break
    case 'column':
      retained = colKeys
      break
    case 'rowGroup': {
      const level = rowKeys.indexOf(calc.percentGroup)
      if (level < 0 || level >= rowKeys.length - 1) throw new Error('占比分组已失效，请重新配置')
      retained = [...rowKeys.slice(0, level + 1), ...colKeys]
      break
    }
    default:
      throw new Error('不支持的占比范围')
  }
  const dimensions = [...rowKeys, ...colKeys]
  const sumFor = (keys: string[]) => {
    const fixed = keys.filter(key => key in query)
    const mask = JSON.stringify([measure, fixed])
    const keyFor = (record: any) => JSON.stringify(fixed.map(key => String(record[key] ?? '')))
    let index = cache.get(mask)
    if (!index) {
      index = new Map()
      for (const record of basis) {
        if (record[measure] == null || record[measure] === '') continue
        const value = new Decimal(record[measure])
        if (!value.isFinite()) continue
        const key = keyFor(record)
        index.set(key, (index.get(key) || new Decimal(0)).add(value))
      }
      cache.set(mask, index)
    }
    return index.get(keyFor(query))
  }
  const numerator = sumFor(dimensions),
    denominator = sumFor(retained)
  return !numerator || !denominator || denominator.isZero()
    ? null
    : numerator.div(denominator).toNumber()
}
