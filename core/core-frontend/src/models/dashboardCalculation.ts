/** Dashboard definitions are persisted; current parameter values belong to each viewer. */
export interface DashboardCalculationConfig {
  version: 1
  timezone: string
  parameters: DashboardParameter[]
  datasetFields: DashboardSharedField[]
}

export interface DashboardParameter {
  id?: string
  name: string
  type: 'DATE'
  required: boolean
  default?:
    | { mode: 'FIXED'; value: string }
    | { mode: 'MACRO'; expression: string }
    | { mode: 'RELATIVE'; relative: DashboardRelativeDate }
}

export interface DashboardRelativeDate {
  base: 'TODAY' | 'YESTERDAY'
  period: 'DAY' | 'WEEK' | 'MONTH' | 'QUARTER' | 'YEAR'
  offset: number
  position: 'START' | 'END'
}

export interface DashboardSharedField {
  id?: string
  datasetGroupId: string
  name: string
  groupType: 'd' | 'q'
  deType: number
  expression: string
}
