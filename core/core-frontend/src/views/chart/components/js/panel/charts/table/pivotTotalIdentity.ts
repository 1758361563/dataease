import { resultKey, type ChartFieldIdentity } from '../../../chartFieldInstance'

type TotalConfig = {
  dataeaseName: string
  aggregation?: string
  originName?: string
  label?: string
}
type TotalField = ChartFieldIdentity & { name?: string; chartShowName?: string }

/** Instance-specific settings take precedence over legacy dataset-field settings. */
export function findPivotTotalConfig<T extends TotalConfig>(
  cfg: T[],
  field: TotalField
): T | undefined {
  return (
    cfg.find(item => item.dataeaseName === resultKey(field)) ||
    cfg.find(item => item.dataeaseName === field.dataeaseName)
  )
}

/** Migrate without mutating the input; repeated placements get independent settings. */
export function normalizePivotTotalConfigs(cfg: TotalConfig[], fields: TotalField[]) {
  return fields.map(field => {
    const previous = findPivotTotalConfig(cfg, field)
    return {
      ...previous,
      dataeaseName: resultKey(field),
      aggregation: previous?.aggregation || 'SUM',
      originName: previous?.originName || '',
      label: previous?.label || field.chartShowName || field.name
    }
  })
}
