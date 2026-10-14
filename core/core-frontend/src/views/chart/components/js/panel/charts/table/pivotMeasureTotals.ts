import { EXTRA_FIELD, ID_SEPARATOR, Node } from '@antv/s2'

/** Repair S2's measure-first grand total, which otherwise has no measure query. */
export function expandMeasureFirstTotals(sheet: any, layout: any) {
  const fields = sheet.dataSet.fields
  if (!fields.valueInCols || fields.columns?.[0] !== EXTRA_FIELD || fields.values.length < 2) return
  const leaves = layout.colLeafNodes
  const groups = leaves.filter(node => node.isGrandTotals && node.query?.[EXTRA_FIELD] == null)
  if (!groups.length) return
  const hierarchy = layout.colsHierarchy
  for (const group of groups) {
    // The original leaf spans all header rows. Use its first row as the group title.
    const titleHeight =
      hierarchy.sampleNodesForAllLevels?.find(node => node.level === group.level)?.height ??
      sheet.options.style?.colCfg?.height ??
      group.height / 2
    const oldHeight = group.height
    group.isLeaf = false
    group.rowIndex = -1
    group.height = Math.min(titleHeight, oldHeight)
    const children = fields.values.map(metric => {
      const child = new Node({
        id: `${group.id}${ID_SEPARATOR}${metric}`,
        key: EXTRA_FIELD,
        field: EXTRA_FIELD,
        value: metric,
        label: sheet.dataSet.getFieldName(metric),
        level: group.level + 1,
        parent: group,
        hierarchy,
        spreadsheet: sheet,
        query: { ...group.query, [EXTRA_FIELD]: metric },
        isLeaf: true,
        isTotals: true,
        isGrandTotals: true,
        isTotalRoot: false,
        isTotalMeasure: true
      })
      child.y = group.y + group.height
      child.height = Math.max(0, oldHeight - group.height)
      sheet.options.layoutCoordinate?.(sheet, null, child)
      const width = sheet.options.style?.colCfg?.width
      child.width = (typeof width === 'function' ? width(child) : width) ?? group.width
      const dragged = sheet.options.style?.colCfg?.widthByFieldValue?.[metric]
      if (typeof dragged === 'number') child.width = dragged
      hierarchy.pushNode(child)
      if (!layout.colNodes.includes(child)) layout.colNodes.push(child)
      return child
    })
    group.children = children
    leaves.splice(leaves.indexOf(group), 1, ...children)
  }
  let x = 0
  leaves.forEach((node, index) => {
    node.x = x
    node.colIndex = index
    node.rowIndex = index
    x += node.width
  })
  const indexNodes = hierarchy.getIndexNodes()
  indexNodes.splice(0, indexNodes.length, ...leaves)
  ;[...layout.colNodes]
    .sort((a, b) => b.level - a.level)
    .forEach(node => {
      const children = node.children?.filter(child => child.width > 0)
      if (!children?.length) return
      node.x = children[0].x
      node.width = children.reduce((sum, child) => sum + child.width, 0)
    })
  hierarchy.width = x
}
