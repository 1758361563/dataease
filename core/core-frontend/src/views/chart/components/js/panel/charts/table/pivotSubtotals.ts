import { ensurePivotDimensionInstances, resultKey } from '../../../chartFieldInstance'

/** Use the same placement identities as the renderer, including old repeated date fields. */
export function getPivotSubtotalOptions(chart: any) {
  const copy = {
    ...chart,
    xAxis: (chart.xAxis || []).map(field => ({ ...field })),
    xAxisExt: (chart.xAxisExt || []).map(field => ({ ...field }))
  }
  ensurePivotDimensionInstances(copy)
  const fields = copy.xAxis.slice(0, -1)
  const names = fields.map(field => field.chartShowName || field.name)
  const labels = fields.map((field, index) => {
    const name = names[index]
    if (names.filter(item => item === name).length < 2) return name
    const dateLabel =
      field.deType === 1
        ? field.dateStyle === 'y_W'
          ? field.dateShowFormat === 'week_range'
            ? '周区间'
            : '周'
          : { y: '年', y_Q: '季度', y_M: '月', M: '月', y_M_d: '日' }[field.dateStyle]
        : undefined
    return dateLabel ? `${name}（${dateLabel}）` : name
  })
  return fields.map((field, index) => ({
    name: resultKey(field),
    legacyName: field.dataeaseName,
    displayName:
      labels.filter(label => label === labels[index]).length > 1
        ? `${labels[index]} · ${index + 1}`
        : labels[index]
  }))
}

/** Explicit placement keys win; otherwise migrate old dataset-field selections. */
export function normalizePivotSubtotalDimensions(chart: any, row: any): string[] {
  const options = getPivotSubtotalOptions(chart)
  if (!row.subTotalsDimensionsNew || row.subTotalsDimensions == null)
    return options.map(option => option.name)
  const selected = new Set(row.subTotalsDimensions)
  const placementKeys = new Set(options.map(option => option.name))
  return options
    .filter(
      option =>
        selected.has(option.name) ||
        (!placementKeys.has(option.legacyName) && selected.has(option.legacyName))
    )
    .map(option => option.name)
}
