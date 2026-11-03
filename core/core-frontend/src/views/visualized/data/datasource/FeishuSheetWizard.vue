<script setup lang="ts">
import AdminHelp from '@/views/company/admin/AdminHelp.vue'
import '@/views/company/admin/admin.less'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { cloneDeep } from 'lodash-es'
import { ElMessage, ElMessageBox } from 'element-plus-secondary'
import { Codemirror } from 'vue-codemirror'
import { sql } from '@codemirror/lang-sql'
import {
  newDataUpdate,
  fetchDataUpdates,
  fetchDataUpdateRuns,
  runDataUpdate,
  cancelDataUpdate,
  deleteDataUpdate,
  dataUpdateStatus
} from '@/api/company/dataUpdate'
import type { DataUpdateDefinition, DataUpdateState, DataUpdateRun } from '@/api/company/dataUpdate'
import {
  checkUpdateDdl,
  executeUpdateDdl,
  readUpdateStructure
} from '@/api/company/dataUpdateSetup'
import type { Structure, DdlOperation } from '@/api/company/dataUpdateSetup'
import {
  newSheetSource,
  sheetConnections,
  sheetWorksheets,
  sheetSample,
  sheetPreview,
  saveSheet,
  legacySheets,
  reconcileSheetRun
} from '@/api/company/sheets'
import type { SheetSource, Worksheet, SheetPreview, LegacySheet } from '@/api/company/sheets'

const emit = defineEmits<{ (e: 'saved', task: DataUpdateDefinition): void }>()
const visible = ref(false)
const step = ref(0)
const tab = ref('setup')
const busy = ref(false)
const task = ref(newDataUpdate('', ''))
const source = ref<SheetSource>(newSheetSource())
const state = ref<DataUpdateState>({
  tasks: [],
  datasources: [],
  instanceId: null,
  hardDisabled: false
})
const connections = ref<Awaited<ReturnType<typeof sheetConnections>>>([])
const worksheets = ref<Worksheet[]>([])
const legacy = ref<LegacySheet[]>([])
const takeover = ref(false)
const headers = ref<string[]>([])
const structure = ref<Structure | null>(null)
const preview = ref<SheetPreview | null>(null)
const tokenKey = ref('')
const saved = ref('')
const records = ref<DataUpdateRun[]>([])
const createTarget = ref(false)
const ddl = ref('')
const ddlOperation = ref<DdlOperation | null>(null)
const ddlChecked = ref('')
const ddlId = ref('')
const extensions = [sql()]
const steps = [{ name: '连接来源' }, { name: '落库与字段' }, { name: '预览与更新' }]
let epoch = 0
let timer: ReturnType<typeof setTimeout> | undefined
const mysql = computed(() => state.value.datasources.filter(d => d.type === 'mysql'))
const grouped = computed(
  () => state.value.tasks.find(t => t.definition.id === task.value.id)?.grouped
)
const latest = computed(
  () => state.value.tasks.find(t => t.definition.id === task.value.id)?.latest
)
const dailyAt = ref('01:00')
const payload = (): DataUpdateDefinition => ({
  ...cloneDeep(task.value),
  sheet: cloneDeep(source.value)
})
const signature = () => JSON.stringify([task.value.datasourceId, task.value.table, source.value])
const dirty = computed(() => saved.value !== JSON.stringify(payload()))
const checked = computed(() => !!preview.value?.token && tokenKey.value === signature())
const nextFire = computed(
  () => state.value.tasks.find(t => t.definition.id === task.value.id)?.nextFireTime
)
const status = (value?: string) =>
  value === 'NEEDS_REVIEW' ? '待核查' : dataUpdateStatus[value || ''] || value || '尚未运行'
const overdue = computed(
  () => state.value.tasks.find(t => t.definition.id === task.value.id)?.freshness === 'OVERDUE'
)
const decimalColumn = (row: { target: string }) =>
  structure.value?.columns.find(c => c.name === row.target && /^(DECIMAL|NUMERIC)/i.test(c.type))
function resetConversion(row: { decimalMode?: string | null; decimalPlaces?: number | null }) {
  row.decimalMode = null
  row.decimalPlaces = null
}
const dateFormats = [
  'yyyy-MM-dd',
  'yyyy/MM/dd',
  'yyyy/M/d',
  'yyyy-M-d',
  'yyyyMMdd',
  'yyyy-MM-dd HH:mm:ss',
  'yyyy/MM/dd HH:mm:ss'
]
const ddlKey = () => JSON.stringify([task.value.datasourceId, task.value.table, ddl.value])
function newId() {
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6] & 15) | 64
  bytes[8] = (bytes[8] & 63) | 128
  const hex = Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(
    16,
    20
  )}-${hex.slice(20)}`
}
async function work(action: () => Promise<void>) {
  if (busy.value) return
  busy.value = true
  try {
    await action()
  } finally {
    busy.value = false
  }
}
function stop() {
  if (timer) clearTimeout(timer)
  timer = undefined
}
async function refresh() {
  const id = task.value.id,
    version = epoch
  if (!id) return
  const [runs, next] = await Promise.all([fetchDataUpdateRuns(id), fetchDataUpdates()])
  if (version !== epoch || !visible.value) return
  records.value = runs
  state.value = next
}
function poll() {
  stop()
  timer = setTimeout(async () => {
    if (!visible.value || !task.value.id) return
    try {
      await refresh()
      if (visible.value) poll()
    } catch {
      stop()
    }
  }, 5000)
}
async function open(datasourceId = '', table = '', existing?: DataUpdateDefinition) {
  stop()
  const version = ++epoch
  visible.value = true
  busy.value = true
  step.value = existing ? 2 : 0
  tab.value = 'setup'
  task.value = existing
    ? cloneDeep(existing)
    : { ...newDataUpdate(datasourceId, table), mode: 'FEISHU', maxRows: 100000 }
  source.value = existing?.sheet ? cloneDeep(existing.sheet) : newSheetSource()
  worksheets.value = []
  headers.value = []
  preview.value = null
  structure.value = null
  records.value = []
  createTarget.value = false
  ddl.value = ''
  ddlOperation.value = null
  ddlChecked.value = ''
  takeover.value = false
  const match = /^0 (\d+) (\d+) \* \* \?$/.exec(task.value.cron)
  dailyAt.value = match ? `${match[2].padStart(2, '0')}:${match[1].padStart(2, '0')}` : '01:00'
  try {
    const [next, apps, old] = await Promise.all([
      fetchDataUpdates(),
      sheetConnections(),
      legacySheets()
    ])
    if (version !== epoch) return
    state.value = next
    connections.value = apps
    legacy.value = old
    if (!source.value.connectionKey)
      source.value.connectionKey = apps.find(a => a.enabled)?.key || ''
    if (existing)
      worksheets.value = [
        {
          sheetId: source.value.sheetId,
          title: source.value.sheetName,
          rowCount: 0,
          columnCount: 0
        }
      ]
    saved.value = JSON.stringify(payload())
    if (existing) {
      await refresh()
      poll()
    }
  } finally {
    if (version === epoch) busy.value = false
  }
}
function adopt(id: string) {
  const item = legacy.value.find(s => s.id === id)
  if (!item) return
  source.value = {
    ...newSheetSource(),
    sourceId: item.id,
    name: item.source_name,
    connectionKey: source.value.connectionKey,
    url: item.spreadsheet_url,
    sheetId: item.sheet_id || '',
    sheetName: item.sheet_name,
    headerRow: item.header_row,
    dataStartRow: item.data_start_row,
    dataEndRow: item.data_end_row,
    columnRange: item.column_range || ''
  }
  task.value.table = item.target_table
  // A schema name is not a unique datasource identity; let the user select the connection explicitly.
  takeover.value = false
  worksheets.value = []
}
async function loadWorksheets() {
  await work(async () => {
    const key = JSON.stringify([source.value.connectionKey, source.value.url])
    const result = await sheetWorksheets(source.value.connectionKey, source.value.url)
    if (key !== JSON.stringify([source.value.connectionKey, source.value.url])) return
    worksheets.value = result.sheets
    if (!worksheets.value.some(s => s.sheetId === source.value.sheetId)) source.value.sheetId = ''
    if (worksheets.value.length === 1) chooseSheet(worksheets.value[0].sheetId)
  })
}
function chooseSheet(id: string) {
  source.value.sheetId = id
  source.value.sheetName = worksheets.value.find(s => s.sheetId === id)?.title || ''
}
async function continueSource() {
  await work(async () => {
    const result = await sheetSample(source.value)
    headers.value = result.headers
    if (!source.value.fields.length)
      source.value.fields = result.headers.map(name => ({
        source: name,
        target: name,
        required: false,
        dateFormat: ''
      }))
    step.value = 1
  })
}
async function inspectTarget() {
  await work(async () => {
    structure.value = await readUpdateStructure(task.value.datasourceId, task.value.table)
    ElMessage.success('已读取目标表结构')
  })
}
async function checkDdl() {
  await work(async () => {
    ddlId.value = newId()
    const result = await checkUpdateDdl({
      operationId: ddlId.value,
      datasourceId: task.value.datasourceId,
      table: task.value.table,
      sql: ddl.value
    })
    if (result.exists) {
      ElMessage.warning('目标表已存在，请使用已有表')
      return
    }
    ddlChecked.value = ddlKey()
    ElMessage.success('建表 SQL 检查通过')
  })
}
async function createTable() {
  await work(async () => {
    ddlOperation.value = await executeUpdateDdl({
      operationId: ddlId.value,
      datasourceId: task.value.datasourceId,
      table: task.value.table,
      sql: ddl.value
    })
    if (ddlOperation.value.status === 'SUCCESS') {
      structure.value = await readUpdateStructure(task.value.datasourceId, task.value.table)
      createTarget.value = false
      ElMessage.success('目标表已创建')
    }
  })
}
async function inspect() {
  await work(async () => {
    const key = signature(),
      result = await sheetPreview(payload())
    if (key !== signature()) return
    preview.value = result
    structure.value = result.target
    tokenKey.value = key
    task.value.structureFingerprint = result.target.fingerprint
    step.value = 2
  })
}
function scheduleChanged() {
  if (task.value.trigger === 'CRON') {
    const [hour, minute] = dailyAt.value.split(':').map(Number)
    task.value.cron = `0 ${minute} ${hour} * * ?`
    task.value.enabled = true
  } else task.value.enabled = false
}
async function save() {
  await work(async () => {
    if (!checked.value) {
      ElMessage.warning('请先预览并校验当前配置')
      return
    }
    const result = await saveSheet(payload(), preview.value.token, takeover.value)
    task.value = result
    source.value = cloneDeep(result.sheet || source.value)
    saved.value = JSON.stringify(payload())
    preview.value = null
    await refresh()
    poll()
    emit('saved', result)
    ElMessage.success('来源已保存，可立即同步或加入任务组')
  })
}
async function run() {
  if (dirty.value) {
    ElMessage.warning('请先保存当前修改')
    return
  }
  await work(async () => {
    await runDataUpdate(task.value.id, task.value.version, '')
    tab.value = 'history'
    await refresh()
    poll()
  })
}
async function cancel(row: DataUpdateRun) {
  await work(async () => {
    await cancelDataUpdate(task.value.id, row.id)
    await refresh()
  })
}
async function remove() {
  // Physical tables are retained when removing an update task.
  try {
    await ElMessageBox.confirm(
      '删除同步任务后保留目标表数据。有关联下游或任务组时，请先调整依赖。',
      '删除同步任务',
      { type: 'warning' }
    )
  } catch {
    return
  }
  await work(async () => {
    await deleteDataUpdate(task.value.id, task.value.version)
    emit('saved', task.value)
    close()
  })
}
async function reconcile(row: DataUpdateRun) {
  try {
    await ElMessageBox.confirm(
      '请确认旧执行已经结束，并核查目标表快照。解除占用后可提交新的同步。',
      '核查并解除占用',
      { type: 'warning' }
    )
  } catch {
    return
  }
  await work(async () => {
    await reconcileSheetRun(row.id)
    await refresh()
  })
}
function close() {
  ++epoch
  stop()
  visible.value = false
}
async function beforeClose(done: () => void) {
  if (busy.value) return
  if (dirty.value) {
    try {
      await ElMessageBox.confirm(
        '尚有未保存的配置，确定离开？已执行的建表操作会保留。',
        '离开接入向导',
        { type: 'warning' }
      )
    } catch {
      return
    }
  }
  close()
  done()
}
watch(
  () => [task.value.datasourceId, task.value.table],
  () => {
    structure.value = null
    ddlChecked.value = ''
  }
)
onBeforeUnmount(() => {
  ++epoch
  stop()
})
defineExpose({ open })
</script>

<template>
  <el-drawer
    v-model="visible"
    size="min(1120px, 96vw)"
    :with-header="false"
    :before-close="beforeClose"
    append-to-body
    class="sheet-drawer"
  >
    <div class="sheet-shell" v-loading="busy">
      <header class="sheet-heading">
        <h2>{{ task.id ? source.name : '连接飞书电子表格' }}</h2>
        <AdminHelp
          content="每个工作表同步到一张 MySQL 目标表。保存后可手动同步、设置计划或加入任务组。"
        />
        <button type="button" class="close-action" aria-label="关闭" @click="beforeClose(() => {})">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path
              d="m6 6 12 12M18 6 6 18"
              stroke="currentColor"
              stroke-width="1.6"
              stroke-linecap="round"
            />
          </svg>
        </button>
      </header>
      <div v-if="task.id" class="status-strip">
        <el-tag v-if="overdue" type="warning">更新已超期</el-tag>
        <div>
          <span>最近执行</span><strong>{{ status(latest?.status) }}</strong>
        </div>
        <div>
          <span>最近成功 · UTC</span><strong>{{ latest?.lastSuccessAt || '等待首次同步' }}</strong>
        </div>
        <div>
          <span>调度</span
          ><strong>{{ grouped ? '由任务组统一调度' : nextFire || '手动更新' }}</strong>
        </div>
        <el-button
          type="primary"
          :disabled="grouped || state.hardDisabled || busy || dirty"
          @click="run"
          >立即同步</el-button
        >
      </div>
      <div class="sheet-tabs" v-if="task.id">
        <button :class="{ selected: tab === 'setup' }" @click="tab = 'setup'">来源与更新</button>
        <button :class="{ selected: tab === 'history' }" @click="tab = 'history'">运行记录</button>
      </div>
      <div v-if="tab === 'setup'" class="sheet-body">
        <aside class="step-nav">
          <button
            v-for="(item, index) in steps"
            :key="item.name"
            :class="{ active: step === index }"
            @click="step = index"
          >
            <span class="step-number">0{{ index + 1 }}</span
            ><b>{{ item.name }}</b>
          </button>
        </aside>
        <main class="step-content">
          <section v-show="step === 0">
            <el-form label-position="top" :disabled="busy">
              <el-form-item v-if="!task.id && legacy.length" label="接管已有来源（可选）">
                <el-select
                  placeholder="新接入，或选择历史来源"
                  :model-value="source.sourceId"
                  @change="adopt"
                  filterable
                >
                  <el-option
                    v-for="item in legacy"
                    :key="item.id"
                    :label="`${item.source_name} · ${item.target_database}.${item.target_table}`"
                    :value="item.id"
                  />
                </el-select>
                <p class="field-note" v-if="source.sourceId">
                  接管前先停用旧调度并等待在途执行结束。目标库表保留，计划在这里重新设置。
                </p>
              </el-form-item>
              <div class="form-grid">
                <el-form-item label="来源名称" required
                  ><el-input
                    v-model="source.name"
                    maxlength="128"
                    placeholder="例如：门店每日手工数据"
                /></el-form-item>
                <el-form-item label="飞书应用连接" required
                  ><el-select v-model="source.connectionKey" placeholder="选择已启用的应用"
                    ><el-option
                      v-for="item in connections"
                      :key="item.key"
                      :label="item.name"
                      :value="item.key"
                      :disabled="!item.enabled" /></el-select
                ></el-form-item>
              </div>
              <el-alert
                v-if="!connections.some(c => c.enabled)"
                type="info"
                :closable="false"
                title="请先在集成中心 → 应用连接配置并启用飞书应用，并为应用授权此表格。"
              />
              <el-form-item label="电子表格地址" required
                ><div class="input-action">
                  <el-input
                    v-model="source.url"
                    placeholder="https://…feishu.cn/sheets/…"
                  /><el-button @click="loadWorksheets">获取工作表</el-button>
                </div></el-form-item
              >
              <el-form-item label="工作表" required
                ><el-select
                  :model-value="source.sheetId"
                  placeholder="选择工作表"
                  @change="chooseSheet"
                  ><el-option
                    v-for="item in worksheets"
                    :key="item.sheetId"
                    :label="item.title"
                    :value="item.sheetId" /></el-select
              ></el-form-item>
              <div class="range-panel">
                <h4>读取范围</h4>
                <div class="form-grid">
                  <el-form-item label="表头行"
                    ><el-input-number
                      v-model="source.headerRow"
                      :min="1"
                      :max="1000000"
                      controls-position="right"
                  /></el-form-item>
                  <el-form-item label="数据起始行"
                    ><el-input-number
                      v-model="source.dataStartRow"
                      :min="source.headerRow + 1"
                      :max="1000000"
                      controls-position="right"
                  /></el-form-item>
                  <el-form-item label="数据结束行"
                    ><el-input-number
                      v-model="source.dataEndRow"
                      :min="source.dataStartRow"
                      :max="1000000"
                      placeholder="不填则读取全部"
                      controls-position="right"
                  /></el-form-item>
                  <el-form-item label="列范围"
                    ><el-input
                      v-model="source.columnRange"
                      placeholder="例如 A:M；留空读取全部列"
                      @change="source.columnRange = source.columnRange.toUpperCase()"
                  /></el-form-item>
                </div>
              </div>
            </el-form>
          </section>
          <section v-show="step === 1">
            <el-form label-position="top" :disabled="busy"
              ><div class="form-grid">
                <el-form-item label="目标数据源" required
                  ><el-select
                    v-model="task.datasourceId"
                    :disabled="!!task.id"
                    placeholder="选择 MySQL 数据源"
                    ><el-option
                      v-for="item in mysql"
                      :key="item.id"
                      :value="item.id"
                      :label="`${item.name} · ${item.schema}`" /></el-select
                ></el-form-item>
                <el-form-item label="目标表" required
                  ><el-input
                    v-model="task.table"
                    :disabled="!!task.id || !!source.sourceId"
                    placeholder="目标表名称"
                /></el-form-item>
              </div>
              <div class="inline-actions">
                <el-checkbox v-if="!task.id && !source.sourceId" v-model="createTarget"
                  >通过 SQL 新建表</el-checkbox
                ><el-button @click="inspectTarget">读取已有表结构</el-button>
              </div>
              <div v-if="createTarget" class="sql-panel">
                <Codemirror
                  v-model="ddl"
                  :extensions="extensions"
                  :style="{ height: '190px' }"
                  placeholder="CREATE TABLE …"
                />
                <div class="inline-actions">
                  <el-button @click="checkDdl">检查 SQL</el-button
                  ><el-button
                    type="primary"
                    :disabled="!ddlChecked || ddlChecked !== ddlKey()"
                    @click="createTable"
                    >创建目标表</el-button
                  >
                </div>
              </div>
              <el-alert
                v-if="ddlOperation && ddlOperation.status !== 'SUCCESS'"
                type="warning"
                :closable="false"
                :title="`建表${ddlOperation.status === 'NEEDS_REVIEW' ? '待核查' : '处理中'}：${
                  ddlOperation.message || ''
                }`"
                description="请到目标数据源的数据更新页查看建表记录，核查并解除占用后继续。"
              />
              <div v-if="structure" class="structure-note">
                已读取 {{ structure.columns.length }} 个目标字段 ·
                {{ structure.indexes.length }} 个索引列定义
              </div>
              <h4>
                字段映射
                <AdminHelp
                  content="
                目标类型以数据库实际结构为准。日期与小数转换失败会保留旧快照。
              "
                />
              </h4>
              <el-table
                :data="source.fields"
                empty-text="返回上一步读取工作表字段"
                max-height="360"
              >
                <el-table-column label="飞书字段" min-width="140"
                  ><template #default="{ row }"
                    ><el-select v-model="row.source" filterable
                      ><el-option
                        v-for="name in headers.length ? headers : source.fields.map(f => f.source)"
                        :key="name"
                        :value="name"
                        :label="name" /></el-select></template
                ></el-table-column>
                <el-table-column label="目标字段" min-width="170"
                  ><template #default="{ row }"
                    ><el-select v-model="row.target" @change="resetConversion(row)" filterable
                      ><el-option
                        v-for="c in structure?.columns.filter(c => !c.generated) || []"
                        :key="c.name"
                        :value="c.name"
                        :label="`${c.name} · ${c.type}${
                          c.type.toLowerCase().includes('decimal')
                            ? `(${c.precision},${c.scale})`
                            : ''
                        }`" /></el-select></template
                ></el-table-column>
                <el-table-column label="字段转换" width="260"
                  ><template #default="{ row }"
                    ><div v-if="decimalColumn(row)" class="decimal-conversion">
                      <el-select
                        :model-value="row.decimalMode || 'PRESERVE'"
                        @update:model-value="row.decimalMode = $event"
                        aria-label="数值处理方式"
                      >
                        <el-option label="四舍五入" value="ROUND" /><el-option
                          label="向下"
                          value="FLOOR"
                        /><el-option label="向上" value="CEILING" /><el-option
                          label="保留"
                          value="PRESERVE"
                        />
                      </el-select>
                      <el-input-number
                        :model-value="row.decimalPlaces ?? decimalColumn(row).scale"
                        @update:model-value="row.decimalPlaces = $event ?? null"
                        :min="0"
                        :max="Math.min(30, decimalColumn(row).scale)"
                        :precision="0"
                        controls-position="right"
                        aria-label="小数位数"
                      />
                      <AdminHelp
                        :content="
                          '小数位数上限 ' +
                          Math.min(30, decimalColumn(row).scale) +
                          '。四舍五入：半值远离零；向下：趋向负无穷；向上：趋向正无穷。保留：不舍入，超出所选位数报错。'
                        "
                      />
                    </div>
                    <el-select
                      v-else-if="
                        structure?.columns.find(
                          c => c.name === row.target && /DATE|TIME/i.test(c.type)
                        )
                      "
                      v-model="row.dateFormat"
                      clearable
                      placeholder="源日期格式：自动识别"
                      ><el-option
                        v-for="format in dateFormats"
                        :key="format"
                        :value="format"
                        :label="format" /></el-select
                    ><span v-else>—</span></template
                  ></el-table-column
                >
                <el-table-column label="必填" width="60"
                  ><template #default="{ row }"><el-checkbox v-model="row.required" /></template
                ></el-table-column>
                <el-table-column width="60"
                  ><template #default="{ $index }"
                    ><el-button text type="danger" @click="source.fields.splice($index, 1)"
                      >移除</el-button
                    ></template
                  ></el-table-column
                >
              </el-table>
              <el-button
                text
                type="primary"
                @click="
                  source.fields.push({ source: '', target: '', required: false, dateFormat: '' })
                "
                >＋ 添加映射</el-button
              >
              <el-form-item label="额外空值标记"
                ><el-select
                  v-model="source.nullValues"
                  multiple
                  filterable
                  allow-create
                  default-first-option
                  placeholder="例如 None；只有显式添加后才视为空值"
              /></el-form-item>
            </el-form>
          </section>
          <section v-show="step === 2">
            <div class="section-title">
              <div>
                <h4>
                  数据预览
                  <AdminHelp
                    content="预览最多 15 行，映射字段显示转换后的结果；执行时会校验完整读取结果。"
                  />
                </h4>
              </div>
              <el-button @click="inspect">{{ preview ? '重新预览' : '预览并校验' }}</el-button>
            </div>
            <template v-if="preview">
              <el-alert
                v-if="preview.errors.length"
                type="error"
                :closable="false"
                title="请先处理字段转换问题"
                ><p v-for="error in preview.errors" :key="error">{{ error }}</p></el-alert
              >
              <el-alert
                v-else
                :type="checked ? 'success' : 'info'"
                :closable="false"
                :title="checked ? '样本与目标结构校验通过' : '配置已改变，请重新预览'"
              />
              <el-table :data="preview.rows" max-height="270" class="preview-table"
                ><el-table-column
                  v-for="(name, index) in preview.headers"
                  :key="name"
                  :label="name"
                  min-width="140"
                  show-overflow-tooltip
                  ><template #default="{ row }">{{
                    row[index] ?? 'NULL'
                  }}</template></el-table-column
                ></el-table
              >
              <p class="field-note">
                工作表网格上限 {{ preview.sheetRows }} 行，包含空行；本次显示
                {{ preview.rows.length }} 行样本。
              </p>
            </template>
            <div v-else class="preview-empty">
              <b>先看数据，再开始同步</b><span>预览将检查字段、必填值、日期格式与数值精度。</span>
            </div>
            <el-form label-position="top" :disabled="busy">
              <div class="range-panel">
                <h4>更新计划</h4>
                <el-alert
                  v-if="grouped"
                  type="info"
                  :closable="false"
                  title="当前任务由任务组统一调度，独立计划暂不执行。"
                />
                <div class="form-grid">
                  <el-form-item label="触发方式"
                    ><el-select v-model="task.trigger" :disabled="grouped" @change="scheduleChanged"
                      ><el-option label="仅手动" value="MANUAL" /><el-option
                        label="每日定时"
                        value="CRON" /><el-option
                        label="上游成功后"
                        value="DEPENDENCY" /></el-select
                  ></el-form-item>
                  <el-form-item v-if="task.trigger === 'CRON'" label="每日时间"
                    ><el-time-picker
                      v-model="dailyAt"
                      format="HH:mm"
                      value-format="HH:mm"
                      :clearable="false"
                      :disabled="grouped"
                      @change="scheduleChanged"
                  /></el-form-item>
                  <el-form-item label="时区"
                    ><el-input v-model="task.timezone" placeholder="Asia/Shanghai"
                  /></el-form-item>
                  <el-form-item v-if="task.trigger === 'DEPENDENCY'" label="自动触发"
                    ><el-switch v-model="task.enabled" :disabled="grouped"
                  /></el-form-item>
                </div>
                <el-form-item label="上游任务（可选）"
                  ><el-select v-model="task.dependencies" multiple filterable
                    ><el-option
                      v-for="item in state.tasks.filter(t => t.definition.id !== task.id)"
                      :key="item.definition.id"
                      :value="item.definition.id"
                      :label="item.definition.table" /></el-select
                ></el-form-item>
              </div>
              <div class="form-grid">
                <el-form-item label="最小有效行数"
                  ><el-input-number
                    v-model="task.minimumRows"
                    :min="0"
                    :max="task.maxRows"
                    controls-position="right" /></el-form-item
                ><el-form-item label="读取行数上限"
                  ><el-input-number
                    v-model="task.maxRows"
                    :min="1"
                    :max="1000000"
                    controls-position="right" /></el-form-item
                ><el-form-item label="执行超时（秒）"
                  ><el-input-number
                    v-model="task.timeoutSeconds"
                    :min="1"
                    :max="86400"
                    controls-position="right" /></el-form-item
                ><el-form-item label="空表处理"
                  ><el-checkbox v-model="task.allowEmpty"
                    >允许空结果清空目标表</el-checkbox
                  ></el-form-item
                >
              </div>
              <el-checkbox v-if="source.sourceId && !task.id" v-model="takeover"
                >已停用旧调度，并确认原同步执行已结束</el-checkbox
              >
            </el-form>
          </section>
        </main>
      </div>
      <div v-else class="history-content">
        <div class="section-title">
          <div>
            <h3>每次同步都有记录</h3>
            <p class="section-note">
              失败时保留最近成功快照。任务组内执行请在目标数据源的任务组批次中查看。
            </p>
          </div>
          <el-button @click="refresh">刷新</el-button>
        </div>
        <el-table :data="records" empty-text="尚未执行同步，点击立即同步开始" max-height="520">
          <el-table-column label="结果" width="110"
            ><template #default="{ row }"
              ><el-tag
                :type="
                  row.status === 'SUCCESS' ? 'success' : row.status === 'FAILED' ? 'danger' : 'info'
                "
                >{{ status(row.status) }}</el-tag
              ></template
            ></el-table-column
          >
          <el-table-column
            prop="requestedAt"
            label="计划 / 请求时间（UTC）"
            min-width="170"
          /><el-table-column prop="finishedAt" label="完成时间（UTC）" min-width="170" />
          <el-table-column prop="rows" label="同步行数" width="100" /><el-table-column
            prop="message"
            label="结果说明"
            min-width="230"
            show-overflow-tooltip
          />
          <el-table-column width="120"
            ><template #default="{ row }"
              ><el-button
                v-if="['QUEUED', 'RUNNING'].includes(row.status)"
                text
                @click="cancel(row)"
                >取消</el-button
              ><el-button
                v-if="['RUNNING', 'NEEDS_REVIEW'].includes(row.status)"
                text
                type="warning"
                @click="reconcile(row)"
                >核查占用</el-button
              ></template
            ></el-table-column
          >
        </el-table>
      </div>
      <footer class="sheet-footer">
        <el-button v-if="task.id" text type="danger" :disabled="busy || grouped" @click="remove"
          >删除任务</el-button
        ><span class="footer-note">{{ task.id ? `目标：${task.table}` : '' }}</span
        ><template v-if="tab === 'setup'"
          ><el-button v-if="step > 0" :disabled="busy" @click="step--">上一步</el-button
          ><el-button v-if="step === 0" type="primary" :disabled="busy" @click="continueSource"
            >下一步</el-button
          ><el-button v-else-if="step === 1" type="primary" :disabled="busy" @click="inspect"
            >预览并继续</el-button
          ><el-button
            v-else
            type="primary"
            :disabled="busy || !checked || !!preview?.errors.length"
            @click="save"
            >保存配置</el-button
          ></template
        >
      </footer>
    </div>
  </el-drawer>
</template>

<style scoped lang="less">
.sheet-shell {
  --ink: #25324b;
  --muted: #7b8699;
  --line: #e9edf3;
  --accent: #5474cb;
  color: var(--ink);
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}
.sheet-heading {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 12px 20px;
  flex-shrink: 0;
  border-bottom: 1px solid var(--line);
  h2 {
    margin: 5px 0;
    font-size: 16px;
    font-weight: 650;
    letter-spacing: -0.5px;
  }
  p {
    margin: 0;
    color: var(--muted);
    font-size: 13px;
  }
}
.eyebrow {
  color: var(--accent);
  font-size: 11px;
  letter-spacing: 2px;
}
.source-mark {
  display: grid;
  place-items: center;
  width: 52px;
  height: 52px;
  flex-shrink: 0;
  border-radius: 16px;
  color: #4f71c5;
  font-size: 16px;
  background: linear-gradient(135deg, #edf5ff, #edf0fb);
  border: 1px solid #dfe8fa;
}
.close-action {
  margin-left: auto;
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
  &:hover {
    background: #f1f4f8;
    color: var(--ink);
  }
  &:focus-visible {
    outline: 2px solid var(--accent);
    outline-offset: 2px;
  }
}
.status-strip {
  margin: 22px 12px 4px;
  display: flex;
  align-items: center;
  gap: 24px;
  padding: 18px 22px;
  background: #f6f8fc;
  border: 1px solid var(--line);
  border-radius: 12px;
  div {
    flex: 1;
    min-width: 0;
  }
  span {
    display: block;
    font-size: 11px;
    color: var(--muted);
    margin-bottom: 7px;
  }
  strong {
    font-size: 13px;
    font-weight: 550;
  }
}
.sheet-tabs {
  display: flex;
  gap: 24px;
  padding: 0 14px;
  border-bottom: 1px solid var(--line);
  button {
    border: 0;
    background: none;
    padding: 18px 0 13px;
    cursor: pointer;
    color: var(--muted);
    border-bottom: 2px solid transparent;
    &.selected {
      color: var(--accent);
      border-color: var(--accent);
      font-weight: 600;
    }
  }
}
.sheet-body {
  display: grid;
  grid-template-columns: 168px minmax(0, 1fr);
  min-height: 0;
  overflow: hidden;
  flex: 1;
  padding-top: 0;
}
.step-nav {
  padding: 16px 10px;
  overflow-y: auto;
  border-right: 1px solid var(--line);
  button {
    width: 100%;
    display: flex;
    align-items: center;
    gap: 11px;
    border: 0;
    background: none;
    padding: 10px 12px;
    margin-bottom: 8px;
    text-align: left;
    cursor: pointer;
    border-radius: 10px;
    color: var(--muted);
    b {
      display: block;
      font-weight: 550;
      margin: 0;
      font-size: 13px;
    }
    small {
      font-size: 11px;
      line-height: 1.6;
    }
    &.active {
      background: #edf2fc;
      color: var(--accent);
    }
  }
}
.step-number {
  font-size: 12px;
  font-weight: 650;
  opacity: 0.8;
  line-height: 20px;
}
.destination-note {
  margin: 30px 12px;
  font-size: 11px;
  line-height: 1.8;
  color: var(--muted);
  b {
    display: block;
    font-size: 12px;
    color: var(--ink);
    margin-top: 4px;
  }
  p {
    margin-top: 10px;
  }
}
.step-content {
  padding: 20px 24px;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  min-width: 0;
  h3 {
    margin: 0 0 8px;
    font-size: 18px;
    font-weight: 600;
  }
  h4 {
    margin: 0 0 16px;
    font-size: 13px;
    font-weight: 600;
  }
  :deep(.ed-form-item) {
    margin-bottom: 20px;
  }
  :deep(.ed-select),
  :deep(.ed-input-number),
  :deep(.ed-time-select) {
    width: 100%;
  }
}
.section-note {
  margin: 0 0 25px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.8;
}
.decimal-conversion {
  display: flex;
  align-items: center;
  gap: 6px;
  :deep(.ed-select) {
    flex: 1;
    min-width: 0;
  }
  :deep(.ed-input-number) {
    width: 80px;
    flex: 0 0 80px;
  }
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 20px;
}
.input-action {
  display: flex;
  width: 100%;
  gap: 10px;
}
.field-note {
  font-size: 11px;
  color: var(--muted);
  line-height: 1.8;
  margin: 6px 0 16px;
}
.range-panel {
  padding: 20px 20px 0;
  background: #f8fafd;
  border: 1px solid var(--line);
  border-radius: 12px;
  margin: 4px 0 24px;
}
.inline-actions {
  display: flex;
  gap: 10px;
  align-items: center;
  margin: 12px 0 20px;
}
.sql-panel {
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  margin-bottom: 20px;
}
.structure-note {
  padding: 12px 15px;
  border-radius: 8px;
  background: #edf7f2;
  color: #408063;
  font-size: 12px;
  margin-bottom: 22px;
}
.section-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.preview-table {
  margin-top: 18px;
  border: 1px solid var(--line);
  border-radius: 8px;
}
.preview-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 42px 20px;
  background: #f8fafd;
  border: 1px dashed #dbe3f0;
  border-radius: 12px;
  margin: 0 0 28px;
  b {
    font-size: 14px;
    font-weight: 550;
  }
  span {
    font-size: 12px;
    color: var(--muted);
  }
}
.sheet-footer {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  background: #fff;
  border-top: 1px solid var(--line);
  padding: 10px 20px;
  margin-top: auto;
}
.footer-note {
  flex: 1;
  font-size: 11px;
  color: var(--muted);
}
.history-content {
  padding: 20px;
  flex: 1;
  min-height: 0;
  overflow: auto;
  h3 {
    font-size: 18px;
    margin: 0 0 8px;
  }
}
@media (max-width: 780px) {
  .sheet-body {
    grid-template-columns: 1fr;
    grid-template-rows: auto minmax(0, 1fr);
  }
  .step-nav {
    display: flex;
    padding: 0 0 18px;
    border: 0;
    gap: 6px;
    button {
      padding: 10px;
    }
    small,
    .destination-note {
      display: none;
    }
  }
  .step-content {
    padding: 16px;
  }
  .form-grid {
    grid-template-columns: 1fr;
  }
  .status-strip {
    flex-wrap: wrap;
  }
  .footer-note {
    display: none;
  }
}
</style>

<style lang="less">
.sheet-drawer.ed-drawer {
  .ed-drawer__body {
    padding: 0;
    height: 100%;
    min-height: 0;
    overflow: hidden;
  }
  .ed-table__empty-block {
    min-height: 100px;
  }
}
</style>
