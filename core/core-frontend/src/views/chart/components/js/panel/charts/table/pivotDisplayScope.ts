import { EXTRA_FIELD } from '@antv/s2'
import { resultKey } from '../../../chartFieldInstance'

export function configurePivotDisplayScope(chart: any, style: any, totals: any) {
  if (style.quotaPosition === 'row') return undefined
  const summaryOnly = chart.yAxis.filter(field => field.pivotDisplayScope === 'totals')
  if (!summaryOnly.length) return undefined
  // Summary-only metrics must have a visible home even if totals were previously disabled.
  totals.col.showGrandTotals = true
  totals.col.reverseLayout = true
  const details = new Set(
    chart.yAxis.filter(field => field.pivotDisplayScope !== 'totals').map(resultKey)
  )
  return (sheet, parent, field, values) => {
    if (parent.isTotals) return values
    // S2 restores every metric when layoutArrange returns an empty list.
    // If all metrics are summary-only, remove detail nodes after hierarchy construction.
    if (field === EXTRA_FIELD && details.size) return values.filter(value => details.has(value))
    return values
  }
}

/** Without detail columns S2 has no normal header sample from which to infer height. */
export function fitSummaryOnlyHeader(sheet: any, layout: any, allSummary = false) {
  if (allSummary) {
    const leaves = layout.colLeafNodes
    leaves.splice(0, leaves.length, ...leaves.filter(node => node.isTotals))
    const roots = new Set(
      layout.colNodes.map(node => node.parent).filter(parent => parent?.id === 'root')
    )
    roots.forEach((root: any) => {
      root.children = root.children.filter(node => node.isTotals)
    })
    const nodes = layout.colNodes
    nodes.splice(0, nodes.length, ...nodes.filter(node => node.isTotals))
    const indexes = layout.colsHierarchy.getIndexNodes()
    indexes.splice(0, indexes.length, ...leaves)
    let x = 0
    leaves.forEach((node, index) => {
      node.x = x
      node.colIndex = index
      node.rowIndex = index
      x += node.width
    })
    ;[...nodes]
      .sort((a, b) => b.level - a.level)
      .forEach(node => {
        if (!node.children?.length) return
        node.x = node.children[0].x
        node.width = node.children.reduce((sum, child) => sum + child.width, 0)
      })
    layout.colsHierarchy.width = x
  }
  if (layout.colsHierarchy.height > 0 || !layout.colLeafNodes.length) return
  if (!layout.colLeafNodes.every(node => node.isTotals)) return
  const fields = sheet.dataSet.fields
  const hidden = sheet.options.style?.colCfg?.hideMeasureColumn && fields.values.length === 1
  const depth = Math.max(1, fields.columns.length - (hidden ? 1 : 0))
  const height = sheet.options.style?.colCfg?.height || 32
  layout.colsHierarchy.height = depth * height
  layout.colsHierarchy.maxLevel = depth - 1
  layout.colNodes.forEach(node => {
    node.y = node.level * height
    const nextLevel = node.children?.length
      ? Math.min(...node.children.map(child => child.level))
      : depth
    node.height = Math.max(1, nextLevel - node.level) * height
  })
}
