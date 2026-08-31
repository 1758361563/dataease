/**
 * Field Role Model — Chart editor contract.
 *
 * DatasetTableField.groupType = Dataset Default Role
 *   （数据集默认更像维度还是指标；只用于分栏 / 新建默认，不构成使用限制）
 *
 * ChartViewField.groupType    = Actual Placement Role
 *   （这一次图表 placement 实际作为 Dimension / Measure）
 *
 * axisConfig.type 不再表示「只接受 Dataset 里哪种默认角色的字段」，
 * 而表示「放到这个轴之后，placement 被赋予什么角色」。
 * 部分轴历史上声明了 type 但仍允许混合语义，必须走 PRESERVE，不能按 type 赋值。
 */

export type AxisRolePolicy = 'ASSIGN_D' | 'ASSIGN_Q' | 'PRESERVE'
export type PlacementRole = 'd' | 'q'

export const ELIGIBILITY_REASON = {
  RECORD_COUNT_AS_DIMENSION: 'error_record_count_as_dimension',
  AGG_FIELD_AS_DIMENSION: 'error_agg_field_as_dimension',
  URL_AS_MEASURE: 'error_url_as_measure'
} as const

export type EligibilityReason = (typeof ELIGIBILITY_REASON)[keyof typeof ELIGIBILITY_REASON]

export type EligibilityResult = { ok: true } | { ok: false; reason: EligibilityReason }

type AxisSpecLike = { type?: 'd' | 'q' } | null | undefined

/** 轴名本身就是混合 / 角色中性，与图表类型无关。 */
const ALWAYS_PRESERVE_AXIS = new Set<string>(['filter', 'extLabel', 'extTooltip'])

/**
 * 即使 axisConfig 声明了 type，也必须 Preserve。
 * bar-range / multi-scatter 的 type:'q' 是历史声明，实际允许时间 Dimension 特例。
 */
const PRESERVE_CHART_AXIS: Record<string, ReadonlySet<string>> = {
  'table-info': new Set(['xAxis']),
  'bar-range': new Set(['yAxis', 'yAxisExt']),
  'multi-scatter': new Set(['xAxis']),
  't-heatmap': new Set(['extColor'])
}

const ASSIGN_Q_CHART_AXIS: Record<string, ReadonlySet<string>> = {
  indicator: new Set(['yAxis']),
  'indicator-combo': new Set(['yAxis'])
}

export function resolveAxisRolePolicy(
  chartType: string | undefined,
  axisName: string,
  axisSpec?: AxisSpecLike
): AxisRolePolicy {
  if (!axisName) {
    return 'PRESERVE'
  }
  if (ALWAYS_PRESERVE_AXIS.has(axisName)) {
    return 'PRESERVE'
  }
  if (chartType && PRESERVE_CHART_AXIS[chartType]?.has(axisName)) {
    return 'PRESERVE'
  }
  if (axisName === 'drillFields' || axisName === 'drill') {
    return 'ASSIGN_D'
  }
  if (chartType && ASSIGN_Q_CHART_AXIS[chartType]?.has(axisName)) {
    return 'ASSIGN_Q'
  }
  if (axisSpec?.type === 'd') {
    return 'ASSIGN_D'
  }
  if (axisSpec?.type === 'q') {
    return 'ASSIGN_Q'
  }
  return 'PRESERVE'
}

export function isRecordCountField(field: any): boolean {
  if (!field) {
    return false
  }
  return field.originName === '*' || String(field.id) === '-1'
}

/**
 * 聚合计算字段：表达式本身已含聚合，不能再作为 Dimension（GROUP BY SUM(x)）。
 * isAgg / agg 来自 DTO；summary === '' 是现有 dragRemoveAggField 的运行时信号。
 */
export function isAggregatedCalcField(field: any): boolean {
  if (!field) {
    return false
  }
  if (field.isAgg === true || field.agg === true) {
    return true
  }
  return field.summary === ''
}

export function checkPlacementEligibility(
  field: any,
  targetRole: PlacementRole
): EligibilityResult {
  if (!field || !targetRole) {
    return { ok: true }
  }
  if (targetRole === 'd') {
    if (isRecordCountField(field)) {
      return { ok: false, reason: ELIGIBILITY_REASON.RECORD_COUNT_AS_DIMENSION }
    }
    if (isAggregatedCalcField(field)) {
      return { ok: false, reason: ELIGIBILITY_REASON.AGG_FIELD_AS_DIMENSION }
    }
  }
  if (targetRole === 'q') {
    if (field.deType === 7) {
      return { ok: false, reason: ELIGIBILITY_REASON.URL_AS_MEASURE }
    }
  }
  return { ok: true }
}

export function resolveTargetRole(policy: AxisRolePolicy, field: any): PlacementRole {
  if (policy === 'ASSIGN_D') {
    return 'd'
  }
  if (policy === 'ASSIGN_Q') {
    return 'q'
  }
  return field?.groupType === 'q' ? 'q' : 'd'
}

/**
 * ASSIGN_Q 默认聚合。不要在多个组件各写一份。
 * deType: 0 text / 1 time / 5 geo → count；2 integer / 3 float / 4 boolean → sum。
 * deType 7 URL 不得进入 Measure，这里只作兜底。
 */
export function resolveDefaultSummary(field: any): string {
  if (!field) {
    return 'count'
  }
  if (isRecordCountField(field)) {
    return 'count'
  }
  const deType = field.deType
  if (deType === 0 || deType === 1 || deType === 5 || deType === 7) {
    return 'count'
  }
  return 'sum'
}

/**
 * 按轴策略写入 ChartViewField.groupType（Actual Placement Role）。
 * PRESERVE：不改 groupType / summary。
 * 已是 Measure 的 placement 保留用户选过的 summary（含聚合计算字段的空 summary）。
 */
export function applyAxisRoleAssignment(field: any, policy: AxisRolePolicy): void {
  if (!field || policy === 'PRESERVE') {
    return
  }
  if (policy === 'ASSIGN_D') {
    field.groupType = 'd'
    field.summary = null
    return
  }
  if (policy === 'ASSIGN_Q') {
    const alreadyMeasure = field.groupType === 'q'
    field.groupType = 'q'
    if (alreadyMeasure) {
      return
    }
    field.summary = resolveDefaultSummary(field)
  }
}
