import { visibleTooltipField, type TooltipFieldIdentity } from '../../../tooltipFieldIdentity'

type MixTooltipItem = {
  name?: string
  data?: {
    quotaList?: TooltipFieldIdentity[]
    valueExt?: unknown
  }
}

type MixTooltipAxisType = 'yAxis' | 'yAxisExt'

function getMixTooltipAxisType(item: MixTooltipItem): MixTooltipAxisType {
  if (
    item?.name === 'valueExt' ||
    Object.prototype.hasOwnProperty.call(item?.data ?? {}, 'valueExt')
  ) {
    return 'yAxisExt'
  }
  return 'yAxis'
}

export function getMixTooltipFormatter<T extends TooltipFieldIdentity>(
  formatterMap: Record<string, T> | readonly T[],
  item: MixTooltipItem
): T | undefined {
  const field = item?.data?.quotaList?.[0]
  const fieldId = field?.id
  if (!field || fieldId == null) {
    return undefined
  }
  const axisType = getMixTooltipAxisType(item)
  if (Array.isArray(formatterMap)) {
    return visibleTooltipField(formatterMap, field, axisType)
  }
  const legacyMap = formatterMap as Record<string, T>
  return legacyMap[`${fieldId}-${axisType}`] ?? legacyMap[fieldId]
}
