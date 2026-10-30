<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus-secondary'
import { Codemirror } from 'vue-codemirror'
import { sql } from '@codemirror/lang-sql'
import {
  cancelDataUpdate,
  deleteDataUpdate,
  dataUpdateStatus,
  dataUpdateTrigger,
  fetchDataUpdateRuns,
  fetchDataUpdates,
  newDataUpdate,
  runDataUpdate,
  saveDataUpdate
} from '@/api/company/dataUpdate'
import type { DataUpdateDefinition, DataUpdateRun, DataUpdateState } from '@/api/company/dataUpdate'
import { inspectUpdate } from '@/api/company/dataUpdateSetup'
import type { Inspection } from '@/api/company/dataUpdateSetup'
const inspection = ref<Inspection | null>(null)
const inspecting = ref(false)
const checkedQuery = ref('')
const checkKey = () =>
  JSON.stringify([
    form.value.sql,
    form.value.sourceDatasourceId,
    form.value.strategy,
    form.value.keys,
    form.value.windowColumn
  ])
const emit = defineEmits<{ (event: 'saved'): void }>()
async function inspect(preview: boolean) {
  inspecting.value = true
  try {
    const signature = checkKey()
    const report = await inspectUpdate(form.value, preview)
    if (signature !== checkKey()) return
    inspection.value = report
    checkedQuery.value = signature
    if (!inspection.value.errors.length)
      form.value.structureFingerprint = inspection.value.target.fingerprint
    else form.value.structureFingerprint = ''
  } finally {
    inspecting.value = false
  }
}

const visible = ref(false)
const loading = ref(false)
const saving = ref(false)
const running = ref(false)
const activeTab = ref('configuration')
const form = ref<DataUpdateDefinition>(newDataUpdate('', ''))
const saved = ref('')
const state = ref<DataUpdateState>({
  tasks: [],
  datasources: [],
  instanceId: null,
  hardDisabled: false
})
const runs = ref<DataUpdateRun[]>([])
const businessDate = ref('')
const sqlExtensions = [sql()]
const keyText = computed({
  get: () => form.value.keys.join(', '),
  set: (value: string) => {
    form.value.keys = value
      .split(/[,，]/)
      .map(key => key.trim())
      .filter(Boolean)
  }
})
const dirty = computed(() => JSON.stringify(form.value) !== saved.value)
const busy = computed(() => runs.value.some(run => ['QUEUED', 'RUNNING'].includes(run.status)))
const currentTask = computed(() =>
  state.value.tasks.find(task => task.definition.id === form.value.id)
)
const upstreamOptions = computed(() =>
  state.value.tasks.filter(task => task.definition.id !== form.value.id)
)
const scheduling = ref('daily')
const dailyAt = ref('03:00')
let generation = 0
let poll: ReturnType<typeof setTimeout> | undefined

function stopPolling() {
  if (poll) clearTimeout(poll)
  poll = undefined
}
async function refreshRuns(token = generation) {
  const id = form.value.id
  if (!id) return
  const result = await fetchDataUpdateRuns(id)
  if (token === generation && visible.value) runs.value = result
}
function startPolling(token: number) {
  stopPolling()
  poll = setTimeout(async () => {
    if (token !== generation || !visible.value) return
    try {
      await refreshRuns(token)
    } catch {
      stopPolling()
      return
    }
    if (token === generation && visible.value) startPolling(token)
  }, 5000)
}
async function open(datasourceId: string, table: string) {
  const token = ++generation
  stopPolling()
  visible.value = true
  loading.value = true
  activeTab.value = 'configuration'
  form.value = newDataUpdate(String(datasourceId), table)
  inspection.value = null
  runs.value = []
  businessDate.value = ''
  saved.value = JSON.stringify(form.value)
  try {
    const result = await fetchDataUpdates()
    if (token !== generation) return
    state.value = result
    form.value.schema =
      result.datasources.find(source => source.id === String(datasourceId))?.schema || ''
    const existing = result.tasks.find(
      task =>
        task.definition.datasourceId === String(datasourceId) && task.definition.table === table
    )
    if (existing) form.value = structuredClone(existing.definition)
    saved.value = JSON.stringify(form.value)
    const daily = /^0 (\d{1,2}) (\d{1,2}) \* \* \?$/.exec(form.value.cron)
    scheduling.value = daily ? 'daily' : 'cron'
    if (daily) dailyAt.value = `${daily[2].padStart(2, '0')}:${daily[1].padStart(2, '0')}`
    await refreshRuns(token)
    startPolling(token)
  } finally {
    if (token === generation) loading.value = false
  }
}
function updateCron() {
  if (scheduling.value === 'daily' && dailyAt.value) {
    const [hour, minute] = dailyAt.value.split(':').map(Number)
    form.value.cron = `0 ${minute} ${hour} * * ?`
  }
}
async function save() {
  if (!form.value.sql.trim()) {
    ElMessage.warning('请填写 SQL')
    return
  }
  if (inspection.value && checkedQuery.value === checkKey()) {
    if (inspection.value.errors.length) {
      ElMessage.warning('请先处理字段检查中的错误')
      return
    }
    if (inspection.value.warnings.length) {
      try {
        await ElMessageBox.confirm(
          '字段检查发现类型转换或可空性差异。确认查询及目标结构满足业务要求后保存。',
          '确认字段差异',
          { confirmButtonText: '确认并保存', cancelButtonText: '继续检查' }
        )
      } catch {
        return
      }
    }
  }
  saving.value = true
  try {
    if (form.value.trigger === 'MANUAL') form.value.enabled = false
    form.value = await saveDataUpdate(form.value)
    saved.value = JSON.stringify(form.value)
    state.value = await fetchDataUpdates()
    ElMessage.success('已保存；自动调度将在 30 秒内同步')
    emit('saved')
  } finally {
    saving.value = false
  }
}
async function run() {
  if (!form.value.id || dirty.value) return
  try {
    await ElMessageBox.confirm(
      `将更新「${form.value.table}」。本次使用已保存的第 ${form.value.version} 版配置。`,
      '立即更新',
      { type: 'warning', confirmButtonText: '开始更新', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  running.value = true
  try {
    await runDataUpdate(form.value.id, form.value.version, businessDate.value || '')
    activeTab.value = 'history'
    await refreshRuns()
    ElMessage.success('已加入更新队列')
  } finally {
    running.value = false
  }
}
async function cancel(run: DataUpdateRun) {
  if (!form.value.id) return
  try {
    await ElMessageBox.confirm(
      '取消后执行器会回滚尚未提交的事务。脚本已提交的 DDL 需要单独检查。',
      '取消本次更新',
      { confirmButtonText: '取消更新', cancelButtonText: '继续执行' }
    )
  } catch {
    return
  }
  await cancelDataUpdate(form.value.id, run.id)
  await refreshRuns()
}
async function beforeClose(done: () => void) {
  if (saving.value || running.value) return
  if (dirty.value) {
    try {
      await ElMessageBox.confirm('配置尚未保存，关闭后将丢弃本次修改。', '关闭数据更新', {
        confirmButtonText: '丢弃修改',
        cancelButtonText: '继续编辑'
      })
    } catch {
      return
    }
  }
  ++generation
  stopPolling()
  done()
}
async function removeConfiguration() {
  const current = form.value
  if (!current.id) return
  try {
    await ElMessageBox.confirm(
      '删除此表的更新配置并停止后续调度。已有运行记录保留在系统中，目标表数据保持原样。',
      '删除更新配置',
      {
        confirmButtonText: '删除配置',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )
  } catch {
    return
  }
  saving.value = true
  try {
    await deleteDataUpdate(current.id, current.version)
    form.value = newDataUpdate(current.datasourceId, current.table)
    form.value.schema = current.schema
    saved.value = JSON.stringify(form.value)
    runs.value = []
    state.value = await fetchDataUpdates()
    ElMessage.success('更新配置已删除')
  } finally {
    saving.value = false
  }
}
function formatUtc(value: string | null) {
  if (!value) return '—'
  const date = new Date(value.includes('T') ? value : `${value.replace(' ', 'T')}Z`)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}
onBeforeUnmount(() => {
  ++generation
  stopPolling()
})
defineExpose({ open })
</script>

<template>
  <el-drawer
    v-model="visible"
    title="数据更新"
    size="min(960px, 96vw)"
    :before-close="beforeClose"
    destroy-on-close
  >
    <div v-loading="loading" class="data-update">
      <div class="update-heading">
        <strong>{{ form.table }}</strong
        ><span>{{ form.id ? `配置版本 ${form.version}` : '尚未配置' }}</span>
      </div>
      <el-alert
        v-if="state.hardDisabled"
        title="运维已关闭数据更新执行，配置仍可保存。"
        type="warning"
        :closable="false"
      />
      <el-alert
        v-else-if="!state.instanceId"
        title="当前实例尚未配置执行标识，请设置 COMPANY_BI_INSTANCE_ID。"
        type="warning"
        :closable="false"
      />
      <el-alert
        v-else-if="form.enabled && form.owner && form.owner !== state.instanceId"
        title="此自动任务属于另一实例。在当前实例保存配置后可接管调度。"
        type="warning"
        :closable="false"
      />
      <el-tabs v-model="activeTab">
        <el-tab-pane label="更新配置" name="configuration">
          <el-form label-position="top" :disabled="loading || saving || busy">
            <section>
              <h3>执行方式</h3>
              <el-form-item label="更新方式"
                ><el-radio-group v-model="form.mode"
                  ><el-radio-button value="QUERY">查询抽取</el-radio-button
                  ><el-radio-button value="SCRIPT">完整 SQL 脚本</el-radio-button></el-radio-group
                ></el-form-item
              >
              <p v-if="form.mode === 'QUERY'" class="hint">
                从选定数据源执行查询，通过字段名映射写入当前表。支持 MySQL、PostgreSQL、SQL Server
                之间抽取；目标表须已存在。
              </p>
              <p v-else class="hint">
                在当前数据源执行完整脚本，由 SQL 决定写入逻辑。脚本中的 DDL
                可能立即提交；请使用分号分隔语句，事务由执行器管理。
              </p>
              <div class="two-columns">
                <el-form-item label="目标 schema / 数据库（来自数据源）"
                  ><el-input v-model="form.schema" disabled placeholder="使用连接默认值"
                /></el-form-item>
                <el-form-item v-if="form.mode === 'QUERY'" label="查询数据源"
                  ><el-select v-model="form.sourceDatasourceId" filterable
                    ><el-option
                      v-for="source in state.datasources"
                      :key="source.id"
                      :value="source.id"
                      :label="`${source.name} · ${source.type}`" /></el-select
                ></el-form-item>
              </div>
              <el-form-item :label="form.mode === 'QUERY' ? 'SELECT 查询' : '刷新脚本'"
                ><Codemirror
                  v-model="form.sql"
                  :extensions="sqlExtensions"
                  :disabled="loading || saving || busy"
                  :style="{ height: '280px', width: '100%' }"
              /></el-form-item>
              <p v-pre class="hint">
                可用参数：{{ window_start }}、{{ window_end }}（已带引号的日期时间），{{
                  target_table
                }}（已转义的目标表名）。参数外不要再加引号。
              </p>
              <template v-if="form.mode === 'QUERY'">
                <el-button :loading="inspecting" @click="inspect(false)">检查字段</el-button>
                <el-button :loading="inspecting" @click="inspect(true)">预览最多 20 行</el-button>
                <p class="hint">
                  检查会访问查询数据源，最长 15 秒。保存后，正式运行会检查目标结构是否变化。
                </p>
                <template v-if="inspection && checkedQuery === checkKey()">
                  <el-alert
                    v-for="message in inspection.errors"
                    :key="message"
                    :title="message"
                    type="error"
                    :closable="false"
                  />
                  <el-alert
                    v-for="message in inspection.warnings"
                    :key="message"
                    :title="message"
                    type="warning"
                    :closable="false"
                  />
                  <el-alert
                    v-if="!inspection.errors.length"
                    title="字段检查完成；实际数据仍会在写入时校验。"
                    type="success"
                    :closable="false"
                  />
                  <el-table :data="inspection.target.columns" max-height="220">
                    <el-table-column prop="name" label="目标字段" /><el-table-column
                      prop="type"
                      label="类型"
                    /><el-table-column prop="precision" label="长度 / 精度" /><el-table-column
                      prop="scale"
                      label="小数位"
                    />
                    <el-table-column prop="defaultValue" label="默认值" />
                    <el-table-column label="可空"
                      ><template #default="{ row }">{{
                        row.nullable ? '是' : '否'
                      }}</template></el-table-column
                    >
                    <el-table-column label="自动生成"
                      ><template #default="{ row }">{{
                        row.generated ? '是' : '否'
                      }}</template></el-table-column
                    >
                  </el-table>
                  <el-table
                    v-if="inspection.target.indexes.length"
                    :data="inspection.target.indexes"
                    max-height="180"
                    ><el-table-column prop="name" label="主键 / 索引" /><el-table-column
                      prop="column"
                      label="字段"
                    /><el-table-column label="唯一"
                      ><template #default="{ row }">{{
                        row.unique ? '是' : '否'
                      }}</template></el-table-column
                    ></el-table
                  >
                  <el-table
                    v-if="inspection.sample.length"
                    :data="inspection.sample"
                    max-height="260"
                  >
                    <el-table-column
                      v-for="(column, index) in inspection.source"
                      :key="index"
                      :label="column.name"
                      :prop="String(index)"
                      min-width="150"
                      show-overflow-tooltip
                    />
                  </el-table>
                </template>
              </template>
            </section>
            <section>
              <h3>更新策略</h3>
              <el-form-item v-if="form.mode === 'QUERY'" label="写入策略"
                ><el-radio-group v-model="form.strategy"
                  ><el-radio value="FULL">全量替换</el-radio
                  ><el-radio value="WINDOW">窗口覆盖</el-radio
                  ><el-radio value="UPSERT">按主键更新</el-radio></el-radio-group
                ></el-form-item
              >
              <p v-if="form.mode === 'QUERY' && form.strategy === 'UPSERT'" class="hint">
                匹配主键时更新查询包含的字段，未匹配时插入；其他已有字段保持原值。查询结果中的重复或空主键会使本次更新失败。
              </p>
              <div class="two-columns">
                <el-form-item label="窗口天数"
                  ><el-input-number v-model="form.windowDays" :min="1" :max="3660" />
                  <p class="hint">
                    窗口为业务日期向前 N 天，包含起点、不包含业务日期当天。
                  </p></el-form-item
                >
                <el-form-item
                  v-if="form.mode === 'QUERY' && form.strategy === 'WINDOW'"
                  label="目标窗口字段"
                  ><el-input v-model="form.windowColumn" placeholder="时间或日期字段名"
                /></el-form-item>
              </div>
              <el-form-item
                v-if="form.mode === 'QUERY'"
                :label="form.strategy === 'UPSERT' ? '匹配主键（必填）' : '去重校验主键（选填）'"
                ><el-input
                  v-model="keyText"
                  placeholder="多个字段用逗号分隔，例如 order_id, line_id"
              /></el-form-item>
              <el-checkbox v-if="form.mode === 'QUERY'" v-model="form.allowEmpty"
                >允许空结果（全量替换会清空表，窗口覆盖会清空对应窗口）</el-checkbox
              >
            </section>
            <section>
              <h3>调度</h3>
              <el-alert
                v-if="currentTask?.grouped"
                title="此任务由任务组统一调度，请在数据更新页签运行整组。"
                type="info"
                :closable="false"
              />
              <el-form-item label="触发方式"
                ><el-radio-group v-model="form.trigger"
                  ><el-radio value="MANUAL">仅手动</el-radio><el-radio value="CRON">定时</el-radio
                  ><el-radio value="DEPENDENCY">上游完成后</el-radio></el-radio-group
                ></el-form-item
              >
              <el-form-item v-if="form.trigger !== 'MANUAL'" label="自动调度"
                ><el-switch v-model="form.enabled" active-text="开启" inactive-text="关闭"
              /></el-form-item>
              <div v-if="form.trigger === 'CRON'" class="two-columns">
                <el-form-item label="更新周期"
                  ><el-select v-model="scheduling" @change="updateCron"
                    ><el-option label="每天" value="daily" /><el-option
                      label="自定义 Cron"
                      value="cron" /></el-select
                ></el-form-item>
                <el-form-item v-if="scheduling === 'daily'" label="执行时间"
                  ><el-time-picker
                    v-model="dailyAt"
                    format="HH:mm"
                    value-format="HH:mm"
                    :clearable="false"
                    @change="updateCron"
                /></el-form-item>
                <el-form-item v-else label="Quartz Cron（秒 分 时 日 月 周）"
                  ><el-input v-model="form.cron" placeholder="0 0 3 * * ?"
                /></el-form-item>
              </div>
              <el-form-item label="业务时区"
                ><el-select v-model="form.timezone" filterable allow-create default-first-option
                  ><el-option label="Asia/Shanghai" value="Asia/Shanghai" /><el-option
                    label="UTC"
                    value="UTC" /></el-select
              ></el-form-item>
              <el-form-item label="上游任务">
                <el-select
                  v-model="form.dependencies"
                  multiple
                  filterable
                  placeholder="选择需要先完成的表"
                  ><el-option
                    v-for="task in upstreamOptions"
                    :key="task.definition.id || ''"
                    :value="task.definition.id || ''"
                    :label="`${
                      state.datasources.find(source => source.id === task.definition.datasourceId)
                        ?.name || task.definition.datasourceId
                    } / ${task.definition.schema ? task.definition.schema + '.' : ''}${
                      task.definition.table
                    }`"
                /></el-select>
                <p class="hint">
                  同一业务日期的上游任务全部成功后才能执行。依赖触发按上游运行组合去重；手动和定时执行也检查上游。修改上游配置后需重新运行上游。
                </p>
              </el-form-item>
              <p v-if="currentTask?.nextFireTime" class="hint">
                下次定时执行：{{ formatUtc(currentTask.nextFireTime) }}
              </p>
              <div class="two-columns">
                <el-form-item label="执行超时（秒）"
                  ><el-input-number
                    v-model="form.timeoutSeconds"
                    :min="1"
                    :max="86400" /></el-form-item
                ><el-form-item v-if="form.mode === 'QUERY'" label="抽取行数上限"
                  ><el-input-number v-model="form.maxRows" :min="1" :max="100000000" :step="10000"
                /></el-form-item>
              </div>
              <el-form-item
                v-if="form.mode === 'QUERY'"
                label="最小抽取行数（0 表示不设置额外下限）"
                ><el-input-number v-model="form.minimumRows" :min="0" :max="form.maxRows"
              /></el-form-item>
            </section>
          </el-form>
        </el-tab-pane>
        <el-tab-pane label="运行记录" name="history">
          <div class="history-toolbar">
            <span>最近 50 次运行 · 时间按浏览器本地时区显示</span
            ><el-button :disabled="!form.id" @click="refreshRuns()">刷新</el-button>
          </div>
          <el-table :data="runs" empty-text="暂无运行记录" max-height="600">
            <el-table-column type="expand"
              ><template #default="{ row }"
                ><div class="run-detail">
                  <p>运行 ID：{{ row.id }}</p>
                  <p>配置版本：{{ row.version }} · 业务日期：{{ row.businessDate }}</p>
                  <p>结束：{{ formatUtc(row.finishedAt) }}</p>
                  <p>{{ row.message || '等待执行结果' }}</p>
                </div></template
              ></el-table-column
            >
            <el-table-column label="提交时间" width="175"
              ><template #default="{ row }">{{
                formatUtc(row.requestedAt)
              }}</template></el-table-column
            >
            <el-table-column label="状态" width="110"
              ><template #default="{ row }"
                ><el-tag
                  :type="
                    row.status === 'SUCCESS'
                      ? 'success'
                      : ['FAILED', 'INTERRUPTED'].includes(row.status)
                      ? 'danger'
                      : 'info'
                  "
                  >{{ dataUpdateStatus[row.status] || row.status }}</el-tag
                ></template
              ></el-table-column
            >
            <el-table-column label="触发" width="90"
              ><template #default="{ row }">{{
                dataUpdateTrigger[row.triggerType] || row.triggerType
              }}</template></el-table-column
            >
            <el-table-column prop="rows" label="处理行数" min-width="100" />
            <el-table-column label="操作" width="85"
              ><template #default="{ row }"
                ><el-button
                  v-if="['QUEUED', 'RUNNING'].includes(row.status)"
                  text
                  type="danger"
                  @click="cancel(row)"
                  >取消</el-button
                ></template
              ></el-table-column
            >
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </div>
    <template #footer
      ><div class="update-footer">
        <el-button
          v-if="form.id"
          type="danger"
          text
          :disabled="loading || saving || running || busy"
          @click="removeConfiguration"
          >删除配置</el-button
        >
        <el-date-picker
          v-model="businessDate"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="业务日期（默认今天）"
          :clearable="true"
        /><el-button
          :disabled="
            !form.id ||
            dirty ||
            busy ||
            loading ||
            state.hardDisabled ||
            !state.instanceId ||
            currentTask?.grouped
          "
          :loading="running"
          @click="run"
          >立即更新</el-button
        ><el-button
          type="primary"
          :loading="saving"
          :disabled="loading || busy || !dirty"
          @click="save"
          >保存配置</el-button
        >
      </div>
      <p v-if="dirty" class="hint">保存配置后可立即更新。</p></template
    >
  </el-drawer>
</template>

<style scoped lang="less">
.data-update {
  padding: 0 4px;
}
.update-heading {
  display: flex;
  align-items: baseline;
  gap: 16px;
  margin-bottom: 20px;
  strong {
    font-size: 20px;
    word-break: break-all;
  }
  span {
    color: #646b78;
    white-space: nowrap;
  }
}
section {
  padding: 4px 0 20px;
  margin-bottom: 20px;
  border-bottom: 1px solid #e8ebf0;
  h3 {
    margin: 12px 0 20px;
    font-size: 16px;
  }
}
.two-columns {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
}
.hint {
  font-size: 12px;
  line-height: 1.7;
  color: #646b78;
  margin: 8px 0;
}
.update-footer,
.history-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
}
.update-footer {
  justify-content: flex-end;
  flex-wrap: wrap;
}
.history-toolbar {
  justify-content: space-between;
  margin: 16px 0;
  font-size: 12px;
  color: #646b78;
}
.run-detail {
  padding: 8px 24px;
  overflow-wrap: anywhere;
}
:deep(.ed-select) {
  width: 100%;
}
:deep(.cm-editor) {
  border: 1px solid #dcdfe6;
  border-radius: 4px;
}
@media (max-width: 680px) {
  .two-columns {
    grid-template-columns: 1fr;
    gap: 0;
  }
}
</style>
