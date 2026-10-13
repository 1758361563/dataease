/** Reorder the measure header, without changing query fields or dimension grouping. */
export function pivotHeaderFields(
  rows: string[],
  columns: string[],
  values: string[],
  style: { quotaPosition?: string; pivotHeaderOrder?: string }
) {
  const valueInCols = style.quotaPosition !== 'row'
  return {
    rows,
    columns,
    values,
    valueInCols,
    ...(valueInCols && style.pivotHeaderOrder === 'measure-first' ? { customValueOrder: 0 } : {})
  }
}
