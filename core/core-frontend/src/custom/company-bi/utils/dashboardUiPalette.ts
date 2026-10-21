/** Resolve only the old built-in neutrals. Branded/user colors stay intact. */
export function dashboardUiColor(value: string, role: 'text' | 'muted' | 'surface') {
  let key = value?.trim().replace(/\s/g, '').toLowerCase() || ''
  const rgb = key.match(/^rgba?\((\d+),(\d+),(\d+)(?:,([\d.]+))?\)$/)
  if (rgb && (rgb[4] === undefined || Number(rgb[4]) === 1))
    key =
      '#' +
      rgb
        .slice(1, 4)
        .map(channel => Number(channel).toString(16).padStart(2, '0'))
        .join('')
  if (/^#[\da-f]{3}$/.test(key))
    key =
      '#' +
      key
        .slice(1)
        .split('')
        .map(c => c + c)
        .join('')
  if (/^#[\da-f]{8}$/.test(key) && key.endsWith('ff')) key = key.slice(0, 7)
  if (role === 'surface') {
    if (['#ffffff', 'white'].includes(key)) return 'var(--bi-bg-surface, #ffffff)'
    if (['#f3f3f3', '#f5f5f5', '#f5f6f7', '#f5f6f8', '#f2f2f2'].includes(key))
      return 'var(--bi-bg-subtle, #f3f5f8)'
    return value
  }
  if (['', 'none', '#000000', '#1f2329', '#646a73', '#575757', '#606266'].includes(key))
    return role === 'text' ? 'var(--bi-text-primary, #37465f)' : 'var(--bi-text-muted, #7b879b)'
  return value
}
