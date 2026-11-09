export function paretoData<T extends { field: string; value: unknown }>(input: T[]) {
  const rows = input
    .map(row => {
      const value = row.value == null || row.value === '' ? 0 : Number(row.value)
      if (!Number.isFinite(value) || value < 0) throw new Error('帕累托图需要非负的有限数值')
      return { ...row, value }
    })
    .sort((a, b) => b.value - a.value || String(a.field).localeCompare(String(b.field)))
  const max = rows.reduce((n, row) => Math.max(n, row.value), 0)
  // Normalize before summing to avoid overflow for large but valid numeric values.
  const total = max ? rows.reduce((n, row) => n + row.value / max, 0) : 0
  let cumulative = 0
  return rows.map((row, index) => {
    cumulative += max ? row.value / max : 0
    return {
      ...row,
      cumulative: total ? (index === rows.length - 1 ? 1 : Math.min(1, cumulative / total)) : null
    }
  })
}
