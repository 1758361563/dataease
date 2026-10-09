type Field = {
  id?: string | number
  name?: string
  chartShowName?: string
  summary?: string
  sort?: string
  customSort?: unknown[]
  fieldInstanceId?: string
  compareCalc?: { type?: string }
}
type Chart = {
  type?: string
  xAxis?: Field[]
  yAxis?: Field[]
  yAxisExt?: Field[]
  xAxisExt?: Field[]
  extStack?: Field[]
  extBubble?: Field[]
  extLabel?: Field[]
  extTooltip?: Field[]
  resultMode?: string
  resultCount?: number
  sortPriority?: {
    id?: string | number
    axis?: string
    fieldInstanceId?: string
    scope?: 'GROUP' | 'GLOBAL'
  }[]
  customAttr?: { basicStyle?: { calcTopN?: boolean } }
}
const axes = ['xAxis', 'yAxis', 'yAxisExt'] as const
const chartTypes = new Set([
  'bar',
  'bar-horizontal',
  'bar-group',
  'bar-stack',
  'bar-group-stack',
  'bar-percentage',
  'bar-group-horizontal',
  'bar-stack-horizontal',
  'bar-percentage-horizontal',
  'line',
  'area',
  'area-stack',
  'pie',
  'pie-donut',
  'chart-mix',
  'chart-mix-group',
  'chart-mix-stack',
  'chart-mix-dual-line'
])
export const hasDisplayLimitPanel = (chart: Chart) => chartTypes.has(chart.type)
export function displayLimitReason(chart: Chart) {
  if (chart.xAxis?.length !== 1)
    return '当前入口需要一个分类字段。多分类组合暂不支持按分类取前 N 项。'
  if (chart.xAxisExt?.length || chart.extStack?.length || chart.extBubble?.length)
    return '当前图表含系列分组，暂不支持按分类取前 N 项；旧的结果数量仅限制数据行，可能截断系列。'
  if (![...(chart.yAxis || []), ...(chart.yAxisExt || [])].length) return '请先添加数值字段。'
  if (chart.type?.startsWith('chart-mix')) {
    const sorts = activeSortFields(chart)
    if (sorts.length !== 1 || sorts.some(item => item.axis === 'xAxis'))
      return '组合图目前仅支持按一个数值字段取前 N 项。请在数值字段菜单选择升序或降序。'
  }
  if (
    [...axes, 'extLabel', 'extTooltip'].some(axis =>
      chart[axis]?.some(field => field.compareCalc?.type && field.compareCalc.type !== 'none')
    )
  )
    return '当前图表含快速计算，暂不支持排名限制，以免先截取数据影响计算结果。'
  if (chart.customAttr?.basicStyle?.calcTopN)
    return '请先关闭样式中的“合并为其他”，再设置排名数量，避免两次截取改变占比。'
  return ''
}
const sortableAxes = [
  'xAxis',
  'yAxis',
  'xAxisExt',
  'yAxisExt',
  'extStack',
  'extBubble',
  'flowMapStartName',
  'flowMapEndName',
  'extColor',
  'drillFields'
]
export function activeSortFields(chart: Chart) {
  const fields = sortableAxes
    .flatMap(axis =>
      (chart[axis] || []).map((field, index) => ({
        ...field,
        axis,
        key: `${axis}:${field.fieldInstanceId || index}`
      }))
    )
    .filter(field => ['asc', 'desc', 'custom_sort'].includes(field.sort))
  const ordered = []
  for (const priority of chart.sortPriority || []) {
    for (const field of fields) {
      if (
        String(field.id) === String(priority.id) &&
        (!priority.axis || priority.axis === field.axis) &&
        (!priority.fieldInstanceId || priority.fieldInstanceId === field.fieldInstanceId) &&
        !ordered.includes(field)
      )
        ordered.push(field)
    }
  }
  return [...ordered, ...fields.filter(field => !ordered.includes(field))]
}
export function displayCountPatch(count?: number) {
  if (count !== undefined && (!Number.isInteger(count) || count < 1 || count > 1000000))
    throw new Error('显示数量必须为 1 至 1000000 的整数。')
  return count === undefined ? { resultMode: 'all' } : { resultMode: 'custom', resultCount: count }
}
