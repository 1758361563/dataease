type SortField = { sort?: string; customSort?: unknown[] }
type SortChart = {
  type?: string
  xAxis?: SortField[]
  yAxis?: SortField[]
  yAxisExt?: SortField[]
  xAxisExt?: unknown[]
  extStack?: unknown[]
  extBubble?: unknown[]
}

export function supportsMixMetricSort(chart: SortChart) {
  return (
    chart.type?.startsWith('chart-mix') &&
    !chart.xAxisExt?.length &&
    !chart.extStack?.length &&
    !chart.extBubble?.length
  )
}

// Sort belongs to the placement, so removing it also removes its sort rule.
export function clearOtherMixSorts(chart: SortChart, selected: SortField, direction: string) {
  if (!supportsMixMetricSort(chart) || direction === 'none') return
  for (const axis of [chart.xAxis, chart.yAxis, chart.yAxisExt]) {
    axis?.forEach(field => {
      if (field !== selected) {
        field.sort = 'none'
        field.customSort = []
      }
    })
  }
}
