import type { LineOptions } from '@antv/g2plot/esm/plots/line'

/** Keep the axis domain and every series' connection order in agreement. */
export function orderLineData(chart: Chart, options: LineOptions): LineOptions {
  const data = options.data
  if (!Array.isArray(data) || !data.length) return options
  const xField = options.xField || 'field'
  const seriesField = options.seriesField || 'category'
  const values = [...new Set(data.map(item => item[xField]))]
  const axis = chart.xAxis?.length === 1 ? chart.xAxis[0] : undefined
  // SQL may return rows ordered by the measure or the series. A date trend still
  // needs a chronological domain; explicit date direction/custom order wins.
  if (axis?.deType === 1 && !chart.drill?.length) {
    const compare = new Intl.Collator('zh-CN', { numeric: true }).compare
    const custom = new Map<string, number>(
      (axis.customSort || []).map((value, index) => [String(value), index])
    )
    values.sort((a, b) => {
      if (a == null || a === '') return b == null || b === '' ? 0 : 1
      if (b == null || b === '') return -1
      if (axis.sort === 'custom_sort') {
        const left = custom.get(String(a)) as number | undefined
        const right = custom.get(String(b)) as number | undefined
        if (left != null || right != null) return (left ?? custom.size) - (right ?? custom.size)
      }
      return compare(String(a), String(b)) * (axis.sort === 'desc' ? -1 : 1)
    })
  }
  const ranks = new Map(values.map((value, index) => [value, index]))
  const series = new Map<any, any[]>()
  data.forEach(item => {
    const key = item[seriesField]
    if (!series.has(key)) series.set(key, [])
    series.get(key).push(item)
  })
  return {
    ...options,
    // Preserve the series order (and palette assignment), as well as the datum
    // metadata used by tooltips, filtering and click linkage.
    data: [...series.values()].flatMap(points =>
      points.sort((a, b) => ranks.get(a[xField]) - ranks.get(b[xField]))
    ),
    meta: {
      ...options.meta,
      [xField]: { ...options.meta?.[xField], type: 'cat', values }
    }
  }
}
