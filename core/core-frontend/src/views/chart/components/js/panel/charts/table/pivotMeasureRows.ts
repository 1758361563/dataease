import { parseJson } from '../../../util'
import { resultKey } from '@/views/chart/components/js/chartFieldInstance'

type PivotAxis = { dataeaseName?: string; fieldInstanceId?: string }

type PivotChartLike = {
  type?: string
  customAttr?: any
  xAxis?: PivotAxis[]
  xAxisExt?: PivotAxis[]
  yAxis?: PivotAxis[]
}

function basicStyleOf(chart: PivotChartLike) {
  const attr = parseJson(chart?.customAttr) ?? chart?.customAttr
  return attr?.basicStyle
}

/**
 * 透视表是否允许 0 个真实行维度。
 * 只在：指标行头 + 平铺。树形、指标列头、其他图表仍要求至少一个行维度。
 * 树形 + 行头 + 空行维度是当前能力边界，不渲染、不改配置、不注入占位维度。
 */
export function pivotAllowsEmptyRowDimension(chart: PivotChartLike): boolean {
  if (chart?.type !== 'table-pivot') {
    return false
  }
  const basicStyle = basicStyleOf(chart)
  if (basicStyle?.quotaPosition !== 'row') {
    return false
  }
  if (basicStyle?.tableLayoutMode === 'tree') {
    return false
  }
  return true
}

/** 树形 + 指标行头 + 没有真实行维度。调用方应直接结束渲染。 */
export function isBlockedMeasureOnlyTree(chart: PivotChartLike): boolean {
  if (chart?.type !== 'table-pivot') {
    return false
  }
  const basicStyle = basicStyleOf(chart)
  return (
    basicStyle?.quotaPosition === 'row' &&
    basicStyle?.tableLayoutMode === 'tree' &&
    !chart.xAxis?.length
  )
}

/**
 * DataEase 交给 S2 的 fields。不追加 $$extra$$，由 S2 在 valueInCols=false 时自己追加。
 */
export function buildPivotS2Fields(chart: PivotChartLike) {
  const basicStyle = basicStyleOf(chart)
  return {
    rows: (chart.xAxis || []).map(field => resultKey(field as any)),
    columns: (chart.xAxisExt || []).map(field => resultKey(field as any)),
    values: (chart.yAxis || []).map(field => resultKey(field as any)),
    valueInCols: !(basicStyle?.quotaPosition === 'row')
  }
}

/** 与 @antv/s2 PivotDataSet.processDataCfg 相同：行头指标时把 $$extra$$ 接到 rows 末尾。 */
export function s2RowsAfterProcess(fields: { rows: string[]; valueInCols: boolean }) {
  if (fields.valueInCols) {
    return fields.rows
  }
  return Array.from(new Set([...fields.rows, '$$extra$$']))
}

export function pivotExportAllowsEmptyRows(chart: PivotChartLike, valueLength: number) {
  return valueLength > 0 && pivotAllowsEmptyRowDimension(chart)
}

/** 行头指标导出：指标名列、数据列、角头。rowLength 是真实行维度个数，不含 $$extra$$。 */
export function rowQuotaGridMeasureCell(rowIndex: number, level: number, colLength: number) {
  return {
    row: rowIndex + 2 + (colLength === 0 ? 1 : colLength - 1),
    col: level + 1
  }
}

export function rowQuotaGridDataCell(
  rowIndex: number,
  colIndex: number,
  rowLength: number,
  maxColHeight: number
) {
  return {
    row: rowIndex + maxColHeight + 1,
    col: rowLength + colIndex + 2
  }
}

export function rowQuotaGridQuotaHeadCell(colLength: number, rowLength: number) {
  return {
    row: colLength === 0 ? 1 : colLength,
    col: rowLength + 1
  }
}

export function rowQuotaGridColLeafCell(level: number, colIndex: number, rowLength: number) {
  return {
    row: level + 1,
    col: colIndex + rowLength + 2
  }
}

export function rowQuotaGridColNodeCell(level: number, colIndex: number, rowLength: number) {
  return {
    row: level + 1,
    col: colIndex + rowLength + 1
  }
}

/**
 * 0 个真实行维度时，列总计的自定义结果按指标落在根上，没有行路径。
 * 有真实行维度时仍按行路径取值，避免 Case 5 变掉。
 */
export function readGridColTotal(
  rowFields: unknown[] = [],
  colTotalData: Record<string, any> | undefined,
  quotaField: string,
  rowPath: string[] = []
) {
  if (!colTotalData || !quotaField) {
    return undefined
  }
  if (!rowFields.length) {
    return colTotalData[quotaField]
  }
  if (!rowPath.length) {
    return undefined
  }
  return rowPath.reduce((current, key) => current?.[key], colTotalData)?.[quotaField]
}
