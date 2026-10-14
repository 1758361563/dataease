import { EXTRA_FIELD, VALUE_FIELD } from '@antv/s2'

/** Totals need their aggregated, formatted values measured, not just the header label. */
export function fitPivotTotalColumns(sheet: any, layout: any) {
  const columns = layout.colLeafNodes || []
  const rows = layout.rowLeafNodes || []
  const theme = sheet.theme.dataCell
  const padding = theme.cell?.padding || {}
  const extraWidth = (padding.left ?? 8) + (padding.right ?? 8) + 12
  // This callback runs inside the facet constructor, before sheet.facet is assigned.
  // Read the hidden-measure fallback from options/dataSet, never a previous facet.
  const measureInfo =
    sheet.options.style?.colCfg?.hideMeasureColumn && sheet.options.hierarchyType !== 'customTree'
      ? { [EXTRA_FIELD]: sheet.dataSet.fields.values[0] }
      : {}
  let changed = false
  for (const column of columns) {
    if (!column.isTotals && !column.isTotalMeasure) continue
    // A width explicitly dragged by the user continues to take precedence.
    if (typeof sheet.options.style?.colCfg?.widthByFieldValue?.[column.value] === 'number') continue
    let width = column.width
    for (const row of rows) {
      const query = { ...row.query, ...column.query, ...measureInfo }
      const cell = sheet.dataSet.getCellData({
        query,
        rowNode: row,
        isTotals: true,
        totalStatus: {
          isRowTotal: row.isGrandTotals,
          isRowSubTotal: row.isSubTotals,
          isColTotal: column.isGrandTotals,
          isColSubTotal: column.isSubTotals
        }
      })
      if (!cell) continue
      const metric = query[EXTRA_FIELD] ?? cell[EXTRA_FIELD]
      const raw = cell[VALUE_FIELD] ?? cell[metric]
      const formatter = sheet.dataSet.getFieldFormatter(metric)
      const text = formatter?.(raw) ?? raw ?? '-'
      const font = theme.bolderText || theme.text
      width = Math.max(width, Math.ceil(sheet.measureTextWidth(String(text), font) + extraWidth))
    }
    if (width > column.width) {
      column.width = width
      changed = true
    }
  }
  if (!changed) return
  let x = 0
  columns.forEach(column => {
    column.x = x
    x += column.width
  })
  // Rebuild parent spans from deepest to shallowest after widening leaf columns.
  ;[...(layout.colNodes || [])]
    .sort((a, b) => b.level - a.level)
    .forEach(node => {
      if (!node.children?.length) return
      const visible = node.children.filter(child => child.width > 0)
      if (!visible.length) return
      node.x = visible[0].x
      node.width = visible.reduce((sum, child) => sum + child.width, 0)
    })
  layout.colsHierarchy.width = x
}
