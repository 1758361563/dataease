/** Use one category domain before G2 translates either view's data to positions. */
export function alignMixCategories<T extends { field?: string }>(
  left: readonly T[],
  right: readonly T[]
) {
  const values = [...new Set([...left, ...right].map(row => row.field))]
  const order = new Map(values.map((field, index) => [field, index]))
  const sort = (rows: readonly T[]) =>
    [...rows].sort((a, b) => order.get(a.field) - order.get(b.field))

  // Keep complete records, including null values, quota identities and tooltip data.
  // A category present on only one axis must not borrow a value from the other.
  return { data: [sort(left), sort(right)], values }
}
