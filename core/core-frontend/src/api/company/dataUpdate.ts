import request from '@/config/axios'

export interface DataUpdateDefinition {
  id: string | null
  version: number
  datasourceId: string
  schema: string
  table: string
  mode: 'QUERY' | 'SCRIPT'
  sourceDatasourceId: string
  sql: string
  strategy: 'FULL' | 'WINDOW' | 'UPSERT'
  keys: string[]
  windowColumn: string
  windowDays: number
  allowEmpty: boolean
  trigger: 'MANUAL' | 'CRON' | 'DEPENDENCY'
  enabled: boolean
  cron: string
  timezone: string
  dependencies: string[]
  timeoutSeconds: number
  maxRows: number
  owner: string | null
  structureFingerprint?: string
  minimumRows?: number
}
export interface DataUpdateTask {
  definition: DataUpdateDefinition
  nextFireTime: string
  effective: boolean
  grouped?: boolean
  latest?: { status: string; lastSuccessAt: string }
}
export interface DataUpdateState {
  tasks: DataUpdateTask[]
  datasources: { id: string; name: string; type: string; schema: string }[]
  instanceId: string | null
  hardDisabled: boolean
}
export interface DataUpdateRun {
  id: string
  taskId: string
  version: number
  triggerType: string
  businessDate: string
  status: string
  requestedAt: string
  startedAt: string | null
  finishedAt: string | null
  rows: number
  message: string
}
const base = '/company/etl/data-update'
export const fetchDataUpdates = async (): Promise<DataUpdateState> =>
  (await request.get({ url: base, loading: false })).data
export const saveDataUpdate = async (data: DataUpdateDefinition): Promise<DataUpdateDefinition> =>
  (await request.post({ url: base, data })).data
export const fetchDataUpdateRuns = async (id: string): Promise<DataUpdateRun[]> =>
  (await request.get({ url: `${base}/${encodeURIComponent(id)}/runs`, loading: false })).data
export const runDataUpdate = (id: string, version: number, businessDate: string) =>
  request.post({ url: `${base}/${encodeURIComponent(id)}/run`, data: { version, businessDate } })
export const cancelDataUpdate = (id: string, run: string) =>
  request.post({ url: `${base}/${encodeURIComponent(id)}/runs/${encodeURIComponent(run)}/cancel` })
export const deleteDataUpdate = (id: string, version: number) =>
  request.delete({ url: `${base}/${encodeURIComponent(id)}`, params: { version } })
export function newDataUpdate(datasourceId: string, table: string): DataUpdateDefinition {
  return {
    id: null,
    version: 0,
    datasourceId,
    schema: '',
    table,
    mode: 'QUERY',
    sourceDatasourceId: datasourceId,
    sql: '',
    strategy: 'FULL',
    keys: [],
    windowColumn: '',
    windowDays: 1,
    allowEmpty: false,
    trigger: 'MANUAL',
    enabled: false,
    cron: '0 0 3 * * ?',
    timezone: 'Asia/Shanghai',
    dependencies: [],
    timeoutSeconds: 3600,
    maxRows: 5000000,
    owner: null,
    minimumRows: 0,
    structureFingerprint: ''
  }
}
export const dataUpdateStatus: Record<string, string> = {
  QUEUED: '等待执行',
  RUNNING: '执行中',
  SUCCESS: '成功',
  FAILED: '失败',
  CANCELLED: '已取消',
  INTERRUPTED: '进程中断',
  SKIPPED: '本次跳过'
}
export const dataUpdateTrigger: Record<string, string> = {
  MANUAL: '手动',
  CRON: '定时',
  DEPENDENCY: '上游依赖'
}
