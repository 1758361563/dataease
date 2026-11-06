const formatter = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hourCycle: 'h23'
})

/** UTC run records (including MySQL DATETIME strings) and explicit-offset instants. */
export function formatBeijingTime(value: string | number | null | undefined, empty = '—'): string {
  if (value === null || value === undefined || value === '') return empty
  let input = value
  if (typeof input === 'string') {
    input = input.trim().replace(' ', 'T')
    if (!input) return empty
    if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:?\d{2})?$/i.test(input))
      return value as string
    if (!/(?:Z|[+-]\d{2}:?\d{2})$/i.test(input)) input += 'Z'
  }
  const date = new Date(input)
  if (Number.isNaN(date.getTime())) return String(value)
  const parts = Object.fromEntries(
    formatter.formatToParts(date).map(part => [part.type, part.value])
  )
  return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}:${parts.second}`
}
