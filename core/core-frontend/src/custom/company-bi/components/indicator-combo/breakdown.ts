export type BreakdownSort = {
  by: 'label' | 'value'
  direction: 'asc' | 'desc'
}

export type BreakdownCard<T = unknown> = {
  key: string
  label: string
  rawValue: unknown
  payload: T
}

type SortField = {
  id?: string | number
  sort?: string
}

type SortableView = {
  xAxis?: SortField[]
  yAxis?: SortField[]
  sortPriority?: SortField[]
}

const numericValue = (value: unknown) => {
  if (value === null || value === undefined || value === '') return null
  const parsed = Number(value)
  return Number.isNaN(parsed) ? null : parsed
}

export function sortBreakdownCards<T>(
  cards: readonly BreakdownCard<T>[],
  sort?: BreakdownSort
): BreakdownCard<T>[] {
  if (!sort) return [...cards]
  return cards
    .map((card, index) => ({ card, index }))
    .sort((left, right) => {
      let compared = 0
      if (sort.by === 'label') {
        compared = left.card.label.localeCompare(right.card.label, undefined, {
          numeric: true,
          sensitivity: 'base'
        })
      } else {
        const a = numericValue(left.card.rawValue)
        const b = numericValue(right.card.rawValue)
        if (a === null && b !== null) return 1
        if (a !== null && b === null) return -1
        if (a !== null && b !== null) compared = a - b
      }
      if (compared === 0) return left.index - right.index
      return sort.direction === 'asc' ? compared : -compared
    })
    .map(item => item.card)
}

export function resolveBreakdownSort(view?: SortableView): BreakdownSort | undefined {
  const dimension = view?.xAxis?.[0]
  const metric = view?.yAxis?.[0]
  const candidates = [dimension, metric].filter(
    field => field && (field.sort === 'asc' || field.sort === 'desc')
  )
  if (!candidates.length) return undefined

  const priority = (view?.sortPriority || []).map(field => String(field.id))
  const selected =
    candidates
      .map((field, index) => ({ field, index }))
      .sort((left, right) => {
        const li = priority.indexOf(String(left.field.id))
        const ri = priority.indexOf(String(right.field.id))
        if (li < 0 && ri < 0) return left.index - right.index
        if (li < 0) return 1
        if (ri < 0) return -1
        return li - ri
      })[0]?.field || dimension

  return {
    by: selected === metric ? 'value' : 'label',
    direction: selected.sort as BreakdownSort['direction']
  }
}
