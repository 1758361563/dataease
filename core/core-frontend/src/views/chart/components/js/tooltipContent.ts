import { findTooltipField, tooltipFieldKey } from './tooltipFieldIdentity'

// Only enable the data shelf for adapters that implement the explicit renderer.
export const supportsTooltipShelf = (type: string) =>
  [
    'bar',
    'bar-group',
    'bar-stack',
    'bar-group-stack',
    'percentage-bar-stack',
    'bar-horizontal',
    'bar-stack-horizontal',
    'percentage-bar-stack-horizontal',
    'line',
    'area',
    'area-stack',
    'pie',
    'pie-donut',
    'treemap',
    'chart-mix',
    'chart-mix-group',
    'chart-mix-stack',
    'chart-mix-dual-line'
  ].includes(type)

export function tooltipAxes(chart) {
  return ['yAxis', 'yAxisExt', 'extBubble'].flatMap(axisType =>
    (chart[axisType] || [])
      .filter(field => field.groupType !== 'd')
      .map(field => ({
        ...field,
        axisType,
        seriesId: field.seriesId || `${field.id}-${axisType}`,
        tooltipSource: 'axis'
      }))
  )
}

export function tooltipDimensions(chart) {
  return ['xAxis', 'xAxisExt', 'extStack'].flatMap(axisType =>
    (chart[axisType] || []).map(field => ({ ...field, axisType, tooltipSource: 'dimension' }))
  )
}

/** Legacy display is materialized only when the user edits content, never just on load. */
export function editableTooltipFields(chart) {
  const tooltip = chart.customAttr.tooltip
  if (tooltip.contentMode === 'explicit') {
    return (tooltip.seriesTooltipFormatter || []).map(field => {
      if (field.tooltipSource !== 'axis') return field
      const axis = findTooltipField(
        tooltipAxes(chart),
        { ...field, summary: undefined },
        field.axisType
      )
      return axis
        ? {
            ...field,
            summary: axis.summary,
            tooltipUnavailable: false,
            ...(!field.tooltipNameOverride
              ? { name: axis.name, chartShowName: axis.chartShowName }
              : {}),
            ...(!field.tooltipFormatOverride ? { formatterCfg: axis.formatterCfg } : {})
          }
        : { ...field, tooltipUnavailable: true }
    })
  }
  const axes = tooltipAxes(chart)
  const saved = tooltip.seriesTooltipFormatter || []
  const measures = saved.length ? saved.filter(field => field.show) : axes
  const fields = measures.map(field => {
    const axis = findTooltipField(axes, field, field.axisType)
    const extra = findTooltipField(chart.extTooltip || [], field)
    return {
      ...field,
      tooltipSource: axis ? 'axis' : extra ? 'extra' : 'unavailable',
      tooltipFormatOverride: true,
      tooltipNameOverride: true,
      show: true
    }
  })
  return [...tooltipDimensions(chart).map(field => ({ ...field, show: true })), ...fields]
}

export function tooltipQueryFields(fields) {
  // Visibility is independent of field configuration; keep hidden rows for reopening.
  return fields.filter(field => field.tooltipSource === 'extra')
}

/** Remember seen placements so explicit removal/clear survives refresh and re-query. */
export function syncTooltipAxes(chart) {
  if (!chart?.customAttr?.tooltip || !supportsTooltipShelf(chart.type)) return
  const tooltip = chart.customAttr.tooltip
  if (tooltip.contentMode !== 'explicit') return
  const axes = tooltipAxes(chart)
  const keys = axes.map(tooltipContentKey)
  const known = tooltip.autoAxisKeys
  if (known) {
    const fields = tooltip.seriesTooltipFormatter || []
    for (const axis of axes) {
      if (
        !known.includes(tooltipContentKey(axis)) &&
        !findTooltipField(
          fields.filter(field => field.tooltipSource === 'axis'),
          axis,
          axis.axisType
        )
      ) {
        fields.push({ ...axis, show: true })
      }
    }
    tooltip.seriesTooltipFormatter = fields
  }
  tooltip.autoAxisKeys = [...new Set([...(known || []), ...keys])]
}

export function tooltipContentKey(field) {
  return (
    field.tooltipItemId ||
    `${field.tooltipSource}:${field.axisType || ''}:${tooltipFieldKey(field)}`
  )
}

/** Explicit contents are produced in shelf order. No hidden axis/title fallback. */
export function explicitTooltipItems(chart, originalItems, formatValue) {
  const fields = editableTooltipFields(chart)
  const result = []
  const items = originalItems || []
  for (const field of fields.filter(field => field.show !== false)) {
    if (field.tooltipSource === 'unavailable' || field.tooltipUnavailable) continue
    const name = field.chartShowName || field.name
    const seen = new Set()
    for (const item of items) {
      const data = item.data || {}
      if (field.tooltipSource === 'dimension') {
        const dimension = data.dimensionList?.find(value => String(value.id) === String(field.id))
        if (dimension && !seen.has(dimension.value)) {
          seen.add(dimension.value)
          result.push({ name, value: dimension.value ?? '—', color: 'grey' })
        }
        continue
      }
      const axisType =
        Object.prototype.hasOwnProperty.call(data, 'valueExt') || item.name === 'valueExt'
          ? 'yAxisExt'
          : 'yAxis'
      const values =
        field.tooltipSource === 'extra'
          ? data.dynamicTooltipValue || []
          : (data.quotaList || []).map(value => ({
              ...value,
              value:
                axisType === 'yAxisExt'
                  ? data.valueExt ?? item.value
                  : data.__DE_PERCENTAGE_STACK_ORIGIN_VALUE__ ?? data.value ?? item.value,
              axisType
            }))
      for (const value of values) {
        if (
          !findTooltipField([field], value, field.tooltipSource === 'axis' ? axisType : undefined)
        )
          continue
        const key = JSON.stringify([
          data.field,
          data.category,
          data.group,
          value.value,
          value.stringValue
        ])
        if (seen.has(key)) continue
        seen.add(key)
        const axis =
          field.tooltipSource === 'axis'
            ? findTooltipField(tooltipAxes(chart), field, field.axisType)
            : undefined
        const format = !field.tooltipFormatOverride && axis ? axis.formatterCfg : field.formatterCfg
        const text =
          value.value == null ? value.stringValue ?? '—' : formatValue(value.value, format)
        const seriesName =
          items.length > 1 && data.category && data.category !== name
            ? `${name} · ${data.category}`
            : name
        result.push({ name: seriesName, value: text, color: item.color || 'grey' })
      }
    }
  }
  return result
}
