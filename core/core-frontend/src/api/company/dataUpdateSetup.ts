import request from '@/config/axios'
import type { DataUpdateDefinition } from './dataUpdate'

const base = '/company/etl/data-update'
export interface StructureColumn {
  name: string
  type: string
  precision: number
  scale: number
  nullable: boolean
  defaultValue: string | null
  generated: boolean
}
export interface Structure {
  columns: StructureColumn[]
  indexes: { name: string; unique: boolean; column: string; position: number }[]
  fingerprint: string
}
export interface Inspection {
  target: Structure
  source: StructureColumn[]
  errors: string[]
  warnings: string[]
  sample: (string | null)[][]
}
export interface DdlRequest {
  operationId: string
  datasourceId: string
  table: string
  sql: string
}
export interface DdlOperation {
  id: string
  table_name: string
  status: string
  completed_steps: number
  message: string
  structure_json?: string
}
export interface UpdateGroup {
  id: string | null
  version: number
  datasourceId: string
  name: string
  tasks: string[]
  enabled: boolean
  cron: string
  timezone: string
  owner: string | null
}
export interface GroupRun {
  id: string
  business_date: string
  status: string
  message: string
  created_at: string
  nodes?: { task_id: string; status: string; row_count: number; message: string }[]
}
export const inspectUpdate = async (
  data: DataUpdateDefinition,
  preview = false
): Promise<Inspection> =>
  (await request.post({ url: `${base}/setup/inspect`, params: { preview }, data })).data
export const readUpdateStructure = async (
  datasourceId: string,
  table: string
): Promise<Structure> =>
  (await request.get({ url: `${base}/setup/structure`, params: { datasourceId, table } })).data
export const checkUpdateDdl = async (
  data: DdlRequest
): Promise<{ exists: boolean; schema: string; statements: string[] }> =>
  (await request.post({ url: `${base}/setup/ddl/check`, data })).data
export const executeUpdateDdl = async (data: DdlRequest): Promise<DdlOperation> =>
  (await request.post({ url: `${base}/setup/ddl`, data })).data
export const fetchUpdateDdl = async (datasourceId: string): Promise<DdlOperation[]> =>
  (await request.get({ url: `${base}/setup/ddl`, params: { datasourceId }, loading: false })).data
export const reconcileUpdateDdl = async (id: string): Promise<DdlOperation> =>
  (await request.post({ url: `${base}/setup/ddl/${encodeURIComponent(id)}/reconcile` })).data
export const fetchUpdateGroups = async (): Promise<UpdateGroup[]> =>
  (await request.get({ url: `${base}/groups`, loading: false })).data
export const saveUpdateGroup = async (data: UpdateGroup): Promise<UpdateGroup> =>
  (await request.post({ url: `${base}/groups`, data })).data
export const runUpdateGroup = (group: UpdateGroup, businessDate: string) =>
  request.post({
    url: `${base}/groups/${encodeURIComponent(group.id || '')}/run`,
    data: { version: group.version, businessDate }
  })
export const deleteUpdateGroup = (group: UpdateGroup) =>
  request.delete({
    url: `${base}/groups/${encodeURIComponent(group.id || '')}`,
    params: { version: group.version }
  })
export const fetchGroupRuns = async (id: string): Promise<GroupRun[]> =>
  (await request.get({ url: `${base}/groups/${encodeURIComponent(id)}/runs`, loading: false })).data
export const fetchGroupRun = async (id: string): Promise<GroupRun> =>
  (await request.get({ url: `${base}/groups/runs/${encodeURIComponent(id)}`, loading: false })).data
export const cancelGroupRun = (id: string) =>
  request.post({ url: `${base}/groups/runs/${encodeURIComponent(id)}/cancel` })
export const reconcileGroupRun = (id: string) =>
  request.post({ url: `${base}/groups/runs/${encodeURIComponent(id)}/reconcile` })
