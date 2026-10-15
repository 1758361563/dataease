import type { DashboardCalculationConfig } from '@/models/dashboardCalculation'

/** Resolve bracket references only, leaving SQL string literals and quoted identifiers intact. */
function replaceReferences(
  expression: string,
  resolve: (reference: string, start: number, end: number) => string | undefined
) {
  let result = ''
  let quote = ''
  for (let index = 0; index < (expression || '').length; index++) {
    const char = expression[index]
    if (quote) {
      result += char
      if (char === quote) {
        if (expression[index + 1] === quote) result += expression[++index]
        else quote = ''
      }
    } else if (["'", '"', '`'].includes(char)) {
      quote = char
      result += char
    } else if (char === '[') {
      const close = expression.indexOf(']', index + 1)
      if (close >= 0 && !expression.slice(index + 1, close).includes('[')) {
        const reference = expression.slice(index + 1, close)
        result += `[${resolve(reference, index, close + 1) ?? reference}]`
        index = close
      } else result += char
    } else result += char
  }
  return result
}

export type FormulaReferenceKind = 'dataset' | 'shared' | 'parameter'
export type FormulaSegment = { text: string; kind?: FormulaReferenceKind }

/** Color only references that exist in the current dataset/dashboard, never literal text. */
export function highlightFormulaReferences(
  expression: string,
  config: DashboardCalculationConfig,
  datasetId: string,
  fields: any[] = []
): FormulaSegment[] {
  const known = new Map<string, FormulaReferenceKind>()
  fields
    .filter(field => field.fieldScope !== 'DASHBOARD')
    .filter(
      field => field.datasetGroupId == null || String(field.datasetGroupId) === String(datasetId)
    )
    .forEach(field => {
      if (field.id != null) known.set(String(field.id), 'dataset')
      if (field.name) known.set(field.name, 'dataset')
    })
  config.datasetFields
    .filter(field => String(field.datasetGroupId) === String(datasetId))
    .forEach(field => {
      if (field.id != null) known.set(String(field.id), 'shared')
      if (field.name) known.set(field.name, 'shared')
    })
  config.parameters.forEach(parameter => {
    if (parameter.id != null) known.set(`DVP.${parameter.id}`, 'parameter')
    if (parameter.name) known.set(`DYNAMIC_PARAMS.${parameter.name}`, 'parameter')
  })
  const segments: FormulaSegment[] = []
  let cursor = 0
  replaceReferences(expression, (reference, start, end) => {
    if (start > cursor) segments.push({ text: expression.slice(cursor, start) })
    segments.push({ text: expression.slice(start, end), kind: known.get(reference) })
    cursor = end
    return undefined
  })
  if (cursor < (expression || '').length) segments.push({ text: expression.slice(cursor) })
  return segments
}

function references(config: DashboardCalculationConfig, datasetId: string, fields: any[]) {
  const known = new Map<string, string>()
  fields
    .filter(field => field.fieldScope !== 'DASHBOARD')
    .filter(
      field => field.datasetGroupId == null || String(field.datasetGroupId) === String(datasetId)
    )
    .forEach(field => {
      if (field.id != null && field.name) known.set(String(field.id), field.name)
    })
  // Live definitions win over the saved dataset API response when a shared field is renamed.
  config.datasetFields
    .filter(field => String(field.datasetGroupId) === String(datasetId))
    .forEach(field => {
      if (field.id != null && field.name) known.set(String(field.id), field.name)
    })
  return known
}

export function displayFormulaReferences(
  expression: string,
  config: DashboardCalculationConfig,
  datasetId: string,
  fields: any[] = []
) {
  const known = references(config, datasetId, fields)
  return replaceReferences(expression, reference => {
    if (reference.startsWith('DVP.')) {
      const parameter = config.parameters.find(item => String(item.id) === reference.slice(4))
      return parameter?.name ? `DYNAMIC_PARAMS.${parameter.name}` : undefined
    }
    const name = known.get(reference)
    // These names would be interpreted as IDs/parameters by the formula parser.
    return name && !/^\d+$|^DVP\.|^DYNAMIC_PARAMS\./.test(name) ? name : undefined
  })
}

export function normalizeFormulaReferences(
  expression: string,
  config: DashboardCalculationConfig,
  datasetId: string,
  fields: any[] = []
) {
  const byName = new Map<string, string | undefined>()
  references(config, datasetId, fields).forEach((name, id) => {
    byName.set(name, byName.has(name) ? undefined : id)
  })
  return replaceReferences(expression, reference => {
    if (reference.startsWith('DYNAMIC_PARAMS.')) {
      const parameter = config.parameters.find(item => item.name === reference.slice(15))
      return parameter?.id ? `DVP.${parameter.id}` : undefined
    }
    if (/^\d+$/.test(reference) || reference.startsWith('DVP.')) return undefined
    return byName.get(reference)
  })
}
