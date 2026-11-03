import request from '@/config/axios'
import type { DataUpdateDefinition } from './dataUpdate'
import type { Structure } from './dataUpdateSetup'

export interface SheetField {
  source: string
  target: string
  required: boolean
  dateFormat: string
  decimalMode?: 'PRESERVE' | 'ROUND' | 'FLOOR' | 'CEILING' | null
  decimalPlaces?: number | null
}
export interface SheetSource {
  sourceId: string | null
  name: string
  connectionKey: string
  url: string
  sheetId: string
  sheetName: string
  headerRow: number
  dataStartRow: number
  dataEndRow: number | null
  columnRange: string
  fields: SheetField[]
  nullValues: string[]
}
export interface Worksheet {
  sheetId: string
  title: string
  rowCount: number
  columnCount: number
}
export interface SheetPreview {
  headers: string[]
  rows: (string | null)[][]
  sheetRows: number
  sampled: boolean
  target: Structure
  errors: string[]
  token: string
}
export interface LegacySheet {
  id: string
  source_name: string
  spreadsheet_url: string
  sheet_id: string
  sheet_name: string
  header_row: number
  data_start_row: number
  data_end_row: number | null
  column_range: string | null
  target_database: string
  target_table: string
  enabled: number
  schema_json: string
  schedule_json: string | null
  latest_success_at: number | null
}
const base = '/company/etl/data-update/sheets'
export const reconcileSheetRun = (id: string) =>
  request.post({ url: `${base}/runs/${encodeURIComponent(id)}/reconcile` })
export const sheetConnections = async (): Promise<
  { key: string; name: string; enabled: boolean }[]
> => (await request.get({ url: `${base}/connections` })).data
export const sheetWorksheets = async (
  connectionKey: string,
  url: string
): Promise<{ title: string; sheets: Worksheet[] }> =>
  (await request.post({ url: `${base}/worksheets`, data: { connectionKey, url } })).data
export const sheetSample = async (
  data: SheetSource
): Promise<Pick<SheetPreview, 'headers' | 'rows' | 'sheetRows' | 'sampled'>> =>
  (await request.post({ url: `${base}/sample`, data })).data
export const sheetPreview = async (data: DataUpdateDefinition): Promise<SheetPreview> =>
  (await request.post({ url: `${base}/preview`, data })).data
export const saveSheet = async (
  definition: DataUpdateDefinition,
  previewToken: string,
  takeoverConfirmed: boolean
): Promise<DataUpdateDefinition> =>
  (await request.post({ url: base, data: { definition, previewToken, takeoverConfirmed } })).data
export const legacySheets = async (): Promise<LegacySheet[]> =>
  (await request.get({ url: `${base}/legacy` })).data
export const newSheetSource = (): SheetSource => ({
  sourceId: null,
  name: '',
  connectionKey: '',
  url: '',
  sheetId: '',
  sheetName: '',
  headerRow: 1,
  dataStartRow: 2,
  dataEndRow: null,
  columnRange: '',
  fields: [],
  nullValues: []
})
