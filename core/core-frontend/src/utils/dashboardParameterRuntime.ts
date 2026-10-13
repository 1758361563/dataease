import { reactive } from 'vue'
import type { DashboardCalculationConfig } from '@/models/dashboardCalculation'

export const emptyDashboardCalculation = (): DashboardCalculationConfig => ({
  version: 1,
  timezone: 'Asia/Shanghai',
  parameters: [],
  datasetFields: []
})

export const dashboardParameterRuntime = reactive({
  sceneId: '',
  resourceTable: 'core',
  reference: '',
  config: emptyDashboardCalculation(),
  defaults: {} as Record<string, string | null>,
  draft: {} as Record<string, string | null>,
  applied: {} as Record<string, string | null>
})

export const initializeDashboardParameters = (sceneId, resourceTable, runtime) => {
  Object.assign(dashboardParameterRuntime, {
    sceneId: String(sceneId),
    resourceTable,
    reference: runtime.reference,
    config: runtime.config,
    defaults: { ...runtime.values },
    draft: { ...runtime.values },
    applied: { ...runtime.values }
  })
}

export const applyDashboardParameters = () => {
  for (const parameter of dashboardParameterRuntime.config.parameters) {
    if (parameter.required && !dashboardParameterRuntime.draft[parameter.id]) {
      throw new Error(`请设置日期参数：${parameter.name}`)
    }
  }
  dashboardParameterRuntime.applied = { ...dashboardParameterRuntime.draft }
  dashboardParameterRuntime.reference = new Date().toISOString()
}

export const hasDashboardQueryButton = (
  components: any[],
  views: Record<string, any> = {}
): boolean =>
  (components || []).some(
    component =>
      (component.component === 'VQuery' &&
        (views[component.id]?.customStyle || component.customStyle)?.btnList?.includes('sure')) ||
      hasDashboardQueryButton(component.componentData || [], views) ||
      hasDashboardQueryButton(
        component.propValue?.filter?.(item => item?.component) || [],
        views
      ) ||
      hasDashboardQueryButton(
        component.propValue?.flatMap?.(item => item?.componentData || []) || [],
        views
      )
  )

export const attachDashboardParameters = (chart: any) => {
  const runtime = dashboardParameterRuntime
  if (!runtime.sceneId || String(chart.sceneId || runtime.sceneId) !== runtime.sceneId) return
  chart.sceneId = chart.sceneId || runtime.sceneId
  chart.resourceTable = runtime.resourceTable
  if (runtime.resourceTable === 'snapshot') {
    chart.calculationConfig = JSON.stringify(runtime.config)
  } else {
    delete chart.calculationConfig
  }
  chart.dashboardParameterValues = { ...runtime.applied }
  chart.dashboardParameterReference = runtime.reference
  if (chart.chartExtRequest) {
    chart.chartExtRequest.dashboardParameterValues = { ...runtime.applied }
    chart.chartExtRequest.dashboardParameterReference = runtime.reference
    if (runtime.config.parameters.length || runtime.config.datasetFields.length)
      chart.chartExtRequest.cache = false
  }
}

export const stripDashboardQueryState = (chart: any) => {
  delete chart.dashboardParameterValues
  delete chart.dashboardParameterReference
  delete chart.calculationConfig
  if (chart.chartExtRequest) {
    delete chart.chartExtRequest.dashboardParameterValues
    delete chart.chartExtRequest.dashboardParameterReference
  }
  return chart
}

/** Editor cards may be new or retain core metadata; their query revision comes from the editor. */
export const attachDashboardEditorContext = (chart: any, info: any, views: Record<string, any>) => {
  if (
    info?.resourceTable !== 'snapshot' ||
    !Object.prototype.hasOwnProperty.call(views || {}, chart.id)
  )
    return
  chart.resourceTable = 'snapshot'
  chart.sceneId = info.id || null
  chart.calculationConfig = info.calculationConfig || JSON.stringify(emptyDashboardCalculation())
  if (dashboardParameterRuntime.sceneId !== String(info.id)) {
    delete chart.dashboardParameterValues
    delete chart.dashboardParameterReference
    if (chart.chartExtRequest) {
      delete chart.chartExtRequest.dashboardParameterValues
      delete chart.chartExtRequest.dashboardParameterReference
    }
  }
}

export const dashboardTemplateStyle = (styleJson: string, calculationConfig?: string) => {
  const style = JSON.parse(styleJson)
  if (calculationConfig) style.dashboardCalculation = JSON.parse(calculationConfig)
  return JSON.stringify(style)
}

export const rebindDashboardTemplate = (
  configJson: string,
  sourceDataset,
  targetDataset,
  sourceFields: any[],
  targetFields: any[],
  chart: any
) => {
  const config: DashboardCalculationConfig = JSON.parse(configJson)
  const ids = new Map<string, string>()
  const aliases = new Map<string, string>()
  const originals = sourceFields.filter(field => !field.chartId && field.fieldScope !== 'DASHBOARD')
  originals.forEach(field => {
    const target = targetFields.find(
      candidate =>
        !candidate.chartId &&
        candidate.name === field.name &&
        (candidate.deType === field.deType ||
          ([2, 3].includes(candidate.deType) && [2, 3].includes(field.deType)))
    )
    if (target) {
      ids.set(String(field.id), String(target.id))
      aliases.set(field.dataeaseName, target.dataeaseName)
    }
  })
  const rewriteExpression = (expression: string) =>
    expression.replace(/\[(\d+)\]/g, (token, id, offset) => {
      if (insideFormulaLiteral(expression, offset)) return token
      if (ids.has(id)) return `[${ids.get(id)}]`
      if (originals.some(field => String(field.id) === id))
        throw new Error(
          `目标数据集缺少兼容字段：${originals.find(field => String(field.id) === id).name}`
        )
      return token
    })
  config.datasetFields.forEach(field => {
    if (String(field.datasetGroupId) === String(sourceDataset)) {
      field.datasetGroupId = String(targetDataset)
      field.expression = rewriteExpression(field.expression)
    }
  })
  const rewrite = (value: any, key = ''): any => {
    if (Array.isArray(value)) return value.map(item => rewrite(item, key))
    if (value && typeof value === 'object')
      return Object.fromEntries(
        Object.entries(value).map(([name, item]) => [name, rewrite(item, name)])
      )
    if (key === 'originName' && typeof value === 'string') return rewriteExpression(value)
    if (key === 'id' && ids.has(String(value))) return ids.get(String(value))
    if (['dataeaseName', 'fieldShortName'].includes(key) && aliases.has(value))
      return aliases.get(value)
    if (
      ['tableId', 'sourceTableId', 'datasetGroupId'].includes(key) &&
      String(value) === String(sourceDataset)
    )
      return String(targetDataset)
    return value
  }
  return { config: JSON.stringify(config), chart: rewrite(chart) }
}

const insideFormulaLiteral = (expression: string, position: number) => {
  let quote = ''
  for (let index = 0; index < position; index++) {
    const character = expression[index]
    if (quote && character === '\\') {
      index++
      continue
    }
    if (quote && character === quote && expression[index + 1] === quote) {
      index++
      continue
    }
    if (["'", '"', '`'].includes(character)) {
      if (!quote) quote = character
      else if (quote === character) quote = ''
    }
  }
  return !!quote
}

let querySequence = 0
const latestQueries = new Map<string, number>()
export const beginDashboardQuery = (chart: any) => {
  const key = `${chart.sceneId || ''}/${chart.resourceTable || 'core'}/${chart.id}`
  const sequence = ++querySequence
  const fingerprint = () =>
    JSON.stringify([
      dashboardParameterRuntime.sceneId,
      dashboardParameterRuntime.resourceTable,
      dashboardParameterRuntime.config,
      dashboardParameterRuntime.applied
    ])
  const captured = fingerprint()
  latestQueries.set(key, sequence)
  return {
    assertCurrent: () => {
      if (latestQueries.get(key) !== sequence || fingerprint() !== captured) {
        const error = new Error('查询已被新的参数或请求取代')
        error.name = 'AbortError'
        throw error
      }
    },
    finish: () => {
      if (latestQueries.get(key) === sequence) latestQueries.delete(key)
    }
  }
}

export const displayDashboardExpression = (
  expression: string,
  config: DashboardCalculationConfig
) =>
  (expression || '').replace(/\[DVP\.(\d+)\]/g, (token, id) => {
    const parameter = config.parameters.find(item => item.id === id)
    return parameter ? `[DYNAMIC_PARAMS.${parameter.name}]` : token
  })
