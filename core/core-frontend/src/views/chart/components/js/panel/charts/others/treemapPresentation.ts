import { compactTooltip } from '../../common/compactTooltip'
import { editableTooltipFields, explicitTooltipItems } from '../../../tooltipContent'
import { visibleTooltipField } from '../../../tooltipFieldIdentity'

/** G2 hierarchy nodes retain the query datum under `data`. */
export function treemapDatum(node: any = {}) {
  return { ...node?.path?.[0]?.data, ...node?.data, ...node }
}

export function treemapTitle(node: any) {
  const datum = treemapDatum(node)
  const value = Object.prototype.hasOwnProperty.call(datum, 'field') ? datum.field : datum.name
  return value == null ? '未分类' : value === '' ? '（空字符串）' : String(value)
}

export function treemapPercent(value: any, total: any, decimals = 2) {
  if (
    value == null ||
    !Number.isFinite(Number(value)) ||
    !Number.isFinite(Number(total)) ||
    Number(total) <= 0
  )
    return '—'
  const precision = Math.max(0, Math.min(10, Math.floor(Number(decimals) || 0)))
  return `${((Number(value) / Number(total)) * 100).toFixed(precision)}%`
}

export function treemapTooltip(
  chart: any,
  total: number,
  formatValue: (value: any, format: any) => any
) {
  const attr = chart.customAttr.tooltip
  if (!attr.show) return false
  const explicit = attr.contentMode === 'explicit'
  const fields = explicit ? editableTooltipFields(chart) : attr.seriesTooltipFormatter || []
  if (explicit && !fields.some(field => field.show !== false && !field.tooltipUnavailable))
    return false
  const decimals =
    attr.reserveDecimalCount ?? (explicit ? 2 : chart.customAttr.label?.reserveDecimalCount ?? 2)
  return compactTooltip(
    {
      shared: false,
      enterable: false,
      showTitle:
        !explicit ||
        !fields.some(field => field.tooltipSource === 'dimension' && field.show !== false),
      title: (_title, node) => treemapTitle(node),
      customItems(items = []) {
        const normalized = (items || []).map(item => ({ ...item, data: treemapDatum(item.data) }))
        if (!normalized.length) return []
        const head = normalized[0]
        const quota = head.data.quotaList?.[0]
        const main = explicit
          ? fields.find(
              field =>
                field.tooltipSource === 'axis' && visibleTooltipField([field], quota, 'yAxis')
            )
          : fields.length
          ? visibleTooltipField(fields, quota, 'yAxis')
          : chart.yAxis?.[0]
        const result = explicit ? explicitTooltipItems(chart, normalized, formatValue) : []
        if (!explicit) {
          if (main)
            result.push({
              color: head.color,
              name: main.chartShowName || main.name,
              value: head.data.value == null ? '—' : formatValue(head.data.value, main.formatterCfg)
            })
          for (const value of head.data.dynamicTooltipValue || []) {
            const field = visibleTooltipField(fields, value)
            if (field)
              result.push({
                color: head.color,
                name: field.chartShowName || field.name,
                value:
                  value.value == null
                    ? value.stringValue ?? '—'
                    : formatValue(value.value, field.formatterCfg)
              })
          }
        }
        if (main && attr.showProportion !== false) {
          // Hierarchy path runs leaf -> parent -> root. Flat query data uses root total.
          const parentTotal = head.data.path?.[1]?.value ?? head.data.parent?.value ?? total
          result.push({
            color: head.color,
            name: '占比',
            value: treemapPercent(head.data.value, parentTotal, decimals)
          })
        }
        return result
      }
    },
    attr.fontSize
  )
}

export function highlightTreemap(plot: any, key: string | null) {
  plot.setState('active', datum => treemapDatum(datum).treemapKey === key, true)
  plot.setState('active', datum => treemapDatum(datum).treemapKey !== key, false)
  plot.setState('inactive', datum => !!key && treemapDatum(datum).treemapKey !== key, true)
  plot.setState('inactive', datum => !key || treemapDatum(datum).treemapKey === key, false)
}
