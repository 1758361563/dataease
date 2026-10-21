import { compactTooltip } from '../../common/compactTooltip'
import { CHART_PALETTES } from '../../../../editor/util/chartPalettes'
export const PIE_CATEGORY_COLORS = CHART_PALETTES[0].colors

const categoryKey = (value: unknown) => `${typeof value}:${JSON.stringify(value)}`

export function pieColorMap(
  data: any[],
  colors: string[],
  fixed: { id: unknown; color: string }[] = []
) {
  const palette = colors?.length ? colors : PIE_CATEGORY_COLORS
  const result = new Map<string, string>()
  let index = 0
  data.forEach(datum => {
    const specified = fixed.find(item => item.id === datum.field)
    if (datum.pieMembers) result.set(datum.pieKey, '#94a3b8')
    else {
      result.set(datum.pieKey, specified?.color || palette[index % palette.length])
      index++
    }
  })
  return result
}

export function readablePieTooltip(tooltip: any, data: any[], fontSize = 14) {
  const compact = compactTooltip(tooltip, fontSize)
  if (!compact) return false
  const byKey = new Map(data.map(datum => [datum.pieKey, datum]))
  return {
    ...compact,
    title: (title: string, datum: any) => {
      const item = byKey.get(title) || datum
      const name =
        item?.field == null ? '未分类' : item.field === '' ? '（空字符串）' : String(item.field)
      return item?.pieMembers ? `${name}（${item.pieMembers.length} 类）` : name
    }
  }
}

export function preparePiePresentation(data: any[], merge = false, count = 8, label = '其他') {
  const rows = data
    .map((datum, index) => ({ ...datum, pieKey: `real:${categoryKey(datum.field)}:${index}` }))
    .filter(datum => Number.isFinite(Number(datum.value)) && Number(datum.value) >= 0)
    .sort((a, b) => Number(b.value) - Number(a.value))
  const total = rows.reduce((sum, datum) => sum + Number(datum.value), 0)
  const limit = Math.max(1, Math.min(100, Math.floor(Number(count) || 8)))
  const members = merge ? rows.slice(limit) : []
  const dynamicTotals: Record<string, number> = {}
  members.forEach(row =>
    row.dynamicTooltipValue?.forEach(item => {
      dynamicTotals[item.fieldId] = (dynamicTotals[item.fieldId] || 0) + Number(item.value || 0)
    })
  )
  const sectors = members.length
    ? [
        ...rows.slice(0, limit),
        {
          ...rows[0],
          field: label || '其他',
          name: label || '其他',
          pieKey: 'aggregate:others',
          value: members.reduce((sum, datum) => sum + Number(datum.value), 0),
          others: true,
          pieMembers: members,
          dimensionList: [],
          dynamicTooltipValue: Object.entries(dynamicTotals).map(([fieldId, value]) => ({
            fieldId,
            value
          }))
        }
      ]
    : rows
  return { rows, sectors, total, excluded: data.length - rows.length, members }
}

export function stablePieColor(
  value: unknown,
  colors: string[],
  fixed: { id: unknown; color: string }[] = []
) {
  const specified = fixed.find(item => item.id === value)
  if (specified?.color) return specified.color
  const palette = colors?.length ? colors : PIE_CATEGORY_COLORS
  let hash = 2166136261
  for (const character of categoryKey(value))
    hash = Math.imul(hash ^ character.charCodeAt(0), 16777619)
  return palette[(hash >>> 0) % palette.length]
}

export function largePiePresentation(
  data: any[],
  width: number,
  height: number,
  fontSize = 12,
  labelLimit = 8
) {
  const compact = width < 480 || height < 220
  const size = Math.max(10, Math.min(24, Number(fontSize) || 12))
  const limit = Math.max(1, Math.min(20, Math.floor(Number(labelLimit) || 8)))
  const budget = compact
    ? Math.min(3, limit)
    : Math.min(limit, Math.max(1, Math.floor((height - 32) / (size + 10))))
  const labels = new Set(
    data
      .map((datum, index) => ({ datum, index }))
      .filter(({ datum }) => Number.isFinite(Number(datum.value)) && Number(datum.value) > 0)
      .sort((a, b) => Number(b.datum.value) - Number(a.datum.value) || a.index - b.index)
      .slice(0, budget)
      .map(({ datum }) => categoryKey(datum.field))
  )
  const gutter = compact ? 12 : Math.min(180, Math.max(100, Math.round(width * 0.2)))
  return {
    compact,
    fontSize: size,
    radius: 0.94,
    padding: compact ? [12, 12, 12, 12] : [12, gutter, 12, gutter],
    labelWidth: Math.max(50, gutter - 18),
    showLabel: (datum: any) => labels.has(categoryKey(datum.field))
  }
}

export function shortenPieName(name: unknown, maxWidth: number, fontSize: number) {
  const text = name == null ? '未分类' : String(name)
  let width = 0,
    result = ''
  for (const character of text) {
    width += /[\u0000-\u007f]/.test(character) ? fontSize * 0.58 : fontSize
    if (width > Math.max(0, maxWidth - fontSize)) return result + '…'
    result += character
  }
  return result
}
