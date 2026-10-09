/** Tooltip configuration and result identity; old charts may only carry a field/axis id. */
export type TooltipFieldIdentity = {
  id?: string | number
  fieldId?: string | number
  fieldInstanceId?: string
  metricInstanceId?: string
  seriesId?: string | number
  axisType?: string
  summary?: string
  show?: boolean
}

const physicalId = (field: TooltipFieldIdentity) => String(field.id ?? field.fieldId ?? '')
const placementId = (field: TooltipFieldIdentity) => field.fieldInstanceId || field.metricInstanceId

export function tooltipFieldKey(field: TooltipFieldIdentity): string {
  return String(placementId(field) || field.seriesId || physicalId(field))
}

/** Resolve before applying visibility: a hidden placement must never inherit another's format. */
export function findTooltipField<T extends TooltipFieldIdentity>(
  fields: readonly T[] = [],
  target: TooltipFieldIdentity,
  axisType = target?.axisType
): T | undefined {
  if (!target) return undefined
  const instance = placementId(target)
  if (instance) {
    const exact = fields.filter(field => placementId(field) === instance)
    if (exact.length === 1) return exact[0]
    if (exact.length > 1) return undefined
  }
  if (!instance && target.seriesId && String(target.seriesId) !== physicalId(target)) {
    const exact = fields.filter(field => field.seriesId === target.seriesId)
    if (exact.length === 1) return exact[0]
    if (exact.length > 1) return undefined
  }
  const id = physicalId(target)
  if (!id) return undefined
  // Once a placement is identified, only genuinely legacy configurations may fall back.
  let candidates = fields.filter(
    field => physicalId(field) === id && (!instance || !placementId(field))
  )
  if (axisType) {
    const onAxis = candidates.filter(
      field => field.axisType === axisType || field.seriesId === `${id}-${axisType}`
    )
    candidates = onAxis.length
      ? onAxis
      : candidates.filter(
          field => !field.axisType && (!field.seriesId || String(field.seriesId) === id)
        )
  }
  if (target.summary) {
    const sameAggregation = candidates.filter(field => field.summary === target.summary)
    candidates = sameAggregation.length
      ? sameAggregation
      : candidates.filter(field => !field.summary)
  }
  return candidates.length === 1 ? candidates[0] : undefined
}

export function visibleTooltipField<T extends TooltipFieldIdentity>(
  fields: readonly T[] = [],
  target: TooltipFieldIdentity,
  axisType?: string
): T | undefined {
  const field = findTooltipField(fields, target, axisType)
  return field?.show ? field : undefined
}

/** Materialize the legacy automatic display in the editor, without changing the saved chart. */
export function reconcileTooltipFields<T extends TooltipFieldIdentity>(
  saved: readonly T[],
  axes: readonly T[],
  available: readonly T[]
): T[] {
  const automatic = saved.length === 0
  const used = new Set<T>()
  const result = axes.map(axis => {
    const previous = findTooltipField(saved, axis, axis.axisType)
    if (previous) used.add(previous)
    return {
      ...axis,
      ...(previous || {}),
      fieldInstanceId: axis.fieldInstanceId,
      metricInstanceId: axis.metricInstanceId,
      axisType: axis.axisType,
      seriesId: tooltipFieldKey(axis),
      summary: axis.summary,
      show: previous ? previous.show : automatic
    } as T
  })
  saved.forEach(field => {
    if (!used.has(field) && !axes.some(axis => tooltipFieldKey(axis) === tooltipFieldKey(field))) {
      result.push({ ...field })
    }
  })
  available.forEach(field => {
    if (!result.some(item => physicalId(item) === physicalId(field))) {
      result.push({ ...field, seriesId: physicalId(field), show: false })
    }
  })
  return result
}
