<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus-secondary'
import request from '@/config/axios'

const base = '/company/etl'
const tab = ref('tasks')
const state = ref<any>(null)
const versions = ref<any[]>([])
const records = ref<any[]>([])
const loading = ref(false)
const acting = ref(false)
const runError = ref('')
const kind = ref('refresh')
const taskFilter = ref('')
const dates = ref<string[]>([])
const form = ref({ dailyAt: '03:00', chainEnabled: true, refreshDatasourceId: '' })
const preview = ref<any[]>([])
const previewOpen = ref(false)
const uploadInput = ref<HTMLInputElement>()
const labels: Record<string, string> = {
  success: '成功',
  fail: '失败',
  blocked: '上游阻塞',
  running: '运行中',
  imported: '待激活',
  active: '已激活',
  retired: '历史版本',
  rejected: '校验失败',
  pending: '未初始化'
}
const manifests = computed(
  () => (kind.value === 'refresh' ? state.value?.manifest : state.value?.testManifest)?.tasks || []
)
const groups = computed(() => {
  const grouped = new Map<string, any[]>()
  for (const row of records.value) {
    const rows = grouped.get(row.planned_at) || []
    rows.push(row)
    grouped.set(row.planned_at, rows)
  }
  return [...grouped].map(([plannedAt, rows]) => ({ plannedAt, rows }))
})
const lastRuns = ref<Record<string, any>>({})
const disabledRun = computed(
  () =>
    acting.value ||
    state.value?.busy ||
    state.value?.hardDisabled ||
    (kind.value === 'refresh' && !state.value?.effective)
)

async function loadState(resetForm = false) {
  const response = await request.get({ url: `${base}/state`, loading: false })
  state.value = response.data
  if (resetForm)
    form.value = {
      dailyAt: response.data.setting.dailyAt,
      chainEnabled: response.data.setting.chainEnabled,
      refreshDatasourceId: String(response.data.setting.refreshDatasourceId || '')
    }
}
async function loadRuns() {
  runError.value = ''
  try {
    const response = await request.get({
      url: `${base}/runs`,
      params: {
        kind: kind.value,
        task: taskFilter.value,
        from: dates.value?.[0] || '',
        to: dates.value?.[1] || ''
      },
      loading: false
    })
    records.value = response.data || []
  } catch {
    runError.value = '运行记录读取失败，请检查刷新数据源及 etl_run_log 表。'
  }
}
async function refresh(resetForm = false) {
  loading.value = true
  try {
    await loadState(resetForm)
    const response = await request.get({ url: `${base}/packages`, loading: false })
    versions.value = response.data || []
    await loadRuns()
    try {
      const latest = await request.get({
        url: `${base}/latest`,
        loading: false
      })
      const byTask: Record<string, any> = {}
      for (const row of latest.data || []) if (!byTask[row.etl_name]) byTask[row.etl_name] = row
      lastRuns.value = byTask
    } catch {
      /* The run tab displays the connection error. */
    }
  } finally {
    loading.value = false
  }
}
async function action(work: () => Promise<unknown>) {
  if (acting.value) return
  acting.value = true
  try {
    await work()
    await refresh()
  } finally {
    acting.value = false
  }
}
async function save() {
  await action(async () => {
    await request.post({
      url: `${base}/settings`,
      data: { ...form.value, refreshDatasourceId: form.value.refreshDatasourceId || null }
    })
    ElMessage.success('设置已保存')
  })
}
async function toggleSwitch() {
  const enabled = !state.value.setting.schedulerEnabled
  let confirmation = ''
  if (enabled) {
    try {
      const result = await ElMessageBox.prompt(
        '确认旧刷新程序已停止后，输入「旧刷新程序已停止」。正式手动运行也使用此开关。',
        '启用正式刷新',
        {
          inputValidator: value => value === '旧刷新程序已停止' || '请输入完整确认语',
          confirmButtonText: '启用',
          cancelButtonText: '取消'
        }
      )
      confirmation = result.value
    } catch {
      return
    }
  }
  await action(() => request.post({ url: `${base}/switch`, data: { enabled, confirmation } }))
}
async function run(fromTask: string | null, single = false) {
  try {
    await ElMessageBox.confirm(
      `将执行${kind.value === 'refresh' ? '正式' : '测试'}${
        single ? '单个任务' : '刷新链'
      }。验收发生在提交之后，验收失败会记录原因并阻塞下游。`,
      '运行数据刷新',
      { confirmButtonText: '运行', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  await action(async () => {
    const response = await request.post({
      url: `${base}/run`,
      data: { kind: kind.value, fromTask, single }
    })
    ElMessage.success(`已提交批次 ${response.data.plannedAt}`)
  })
}
async function upload(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  if (file.size > 16 * 1024 * 1024) {
    ElMessage.error('SQL 包不能超过 16 MB')
    input.value = ''
    return
  }
  const data = new FormData()
  data.append('file', file)
  try {
    await action(async () => {
      const response = await request.post({
        url: `${base}/packages`,
        data,
        headersType: 'multipart/form-data'
      })
      if (response.data.status === 'rejected') ElMessage.error(response.data.reason)
      else ElMessage.success('校验通过，可激活此版本')
    })
  } finally {
    input.value = ''
  }
}
async function activate(row: any) {
  try {
    await ElMessageBox.confirm(
      `将版本 #${row.id} 设为${row.package_kind === 'test' ? '测试' : '正式'}生效版本。`,
      '激活 SQL 版本',
      { confirmButtonText: '激活', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  await action(() => request.post({ url: `${base}/packages/${row.id}/activate` }))
}
async function initialize(row: any) {
  let confirmation: string
  try {
    const result = await ElMessageBox.prompt(
      '初始化会执行包内的测试建表脚本，每个包执行一次。输入「初始化测试表」。',
      '初始化测试表',
      {
        inputValidator: value => value === '初始化测试表' || '请输入完整确认语',
        confirmButtonText: '初始化',
        cancelButtonText: '取消'
      }
    )
    confirmation = result.value
  } catch {
    return
  }
  await action(() =>
    request.post({ url: `${base}/packages/${row.id}/initialize`, data: { confirmation } })
  )
}
async function showFiles(id: number) {
  const response = await request.get({ url: `${base}/packages/${id}/files` })
  preview.value = response.data
  previewOpen.value = true
}
let timer: ReturnType<typeof setInterval>
onMounted(async () => {
  await refresh(true)
  timer = setInterval(async () => {
    if (loading.value || acting.value) return
    try {
      const wasBusy = state.value?.busy
      await loadState()
      if (wasBusy || state.value?.busy) await refresh()
    } catch {
      /* Manual refresh remains available. */
    }
  }, 10000)
})
onUnmounted(() => clearInterval(timer))
</script>

<template>
  <div class="etl-page" v-loading="loading">
    <header class="etl-header">
      <div>
        <h2>数据刷新</h2>
        <p>每日刷新、SQL 版本与批次验收 · 上海时间</p>
      </div>
      <el-button :disabled="loading" @click="refresh()">刷新状态</el-button>
    </header>
    <template v-if="state">
      <el-alert
        :type="state.effective ? 'success' : 'info'"
        :closable="false"
        :title="
          state.effective
            ? `正式刷新已启用 · ${state.setting.chainEnabled ? `下次执行 ${state.nextFireTime || '触发器尚未注册'}` : '整条链已暂停'}`
            : state.reason
        "
      />
      <el-alert
        v-if="state.timezoneWarning"
        type="warning"
        :closable="false"
        title="JVM 默认时区不是上海时间。任务窗口和调度已显式使用上海时间。"
      />
      <div class="etl-status">
        <el-tag :type="state.busy ? 'warning' : 'info'">{{ state.busy ? '执行中' : '空闲' }}</el-tag
        ><span>{{ state.message }}</span>
      </div>
      <el-tabs v-model="tab">
        <el-tab-pane label="任务" name="tasks">
          <div class="etl-toolbar">
            <el-radio-group v-model="kind" @change="loadRuns"
              ><el-radio-button label="refresh">正式</el-radio-button
              ><el-radio-button label="refresh_test">测试</el-radio-button></el-radio-group
            >
            <el-button type="primary" :disabled="disabledRun" @click="run(null)"
              >运行整条链</el-button
            >
            <span v-if="kind === 'refresh_test'">测试运行时段：05:00–次日 02:30</span>
          </div>
          <el-table :data="state.tasks" border>
            <el-table-column prop="etl_name" label="任务" min-width="180" />
            <el-table-column label="目标表 / 窗口" min-width="200"
              ><template #default="{ row }"
                ><div>
                  {{
                    manifests.find(t => t.etlName === row.etl_name)?.targetTable || '待激活 SQL 包'
                  }}
                </div>
                <small>{{
                  manifests.find(t => t.etlName === row.etl_name)?.windowField
                }}</small></template
              ></el-table-column
            >
            <el-table-column label="依赖" min-width="150"
              ><template #default="{ row }">{{
                manifests.find(t => t.etlName === row.etl_name)?.dependsOn?.join('、') || '无'
              }}</template></el-table-column
            >
            <el-table-column label="启用" width="80"
              ><template #default="{ row }"
                ><el-switch
                  :model-value="!!row.enabled"
                  :disabled="acting || state.busy"
                  @change="
                    value =>
                      action(() =>
                        request.post({
                          url: `${base}/tasks/${encodeURIComponent(row.etl_name)}`,
                          data: { enabled: !!value }
                        })
                      )
                  " /></template
            ></el-table-column>
            <el-table-column label="最近正式运行" min-width="240"
              ><template #default="{ row }"
                ><div>{{ labels[lastRuns[row.etl_name]?.status] || '暂无记录' }}</div>
                <small
                  >{{ lastRuns[row.etl_name]?.started_at }} →
                  {{ lastRuns[row.etl_name]?.finished_at || '—' }}</small
                >
                <div>{{ lastRuns[row.etl_name]?.note }}</div></template
              ></el-table-column
            >
            <el-table-column label="操作" width="210"
              ><template #default="{ row }"
                ><el-button
                  link
                  type="primary"
                  :disabled="disabledRun || !row.enabled"
                  @click="run(row.etl_name, true)"
                  >只跑这个</el-button
                ><el-button
                  link
                  type="primary"
                  :disabled="disabledRun || !row.enabled"
                  @click="run(row.etl_name)"
                  >从这里往下跑</el-button
                ></template
              ></el-table-column
            >
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="运行记录" name="runs">
          <div class="etl-toolbar">
            <el-select v-model="kind" @change="loadRuns"
              ><el-option label="正式" value="refresh" /><el-option
                label="测试"
                value="refresh_test" /></el-select
            ><el-select v-model="taskFilter" clearable placeholder="全部任务" @change="loadRuns"
              ><el-option
                v-for="task in state.tasks"
                :key="task.etl_name"
                :label="task.etl_name"
                :value="task.etl_name" /></el-select
            ><el-date-picker
              v-model="dates"
              type="daterange"
              value-format="YYYY-MM-DD"
              start-placeholder="开始日期"
              end-placeholder="结束日期"
              @change="loadRuns"
            />
          </div>
          <el-alert v-if="runError" type="error" :title="runError" :closable="false" />
          <el-empty v-else-if="!groups.length" description="暂无运行记录" />
          <section v-for="group in groups" :key="group.plannedAt" class="etl-batch">
            <h3>
              {{ group.plannedAt }}
              <small>{{ group.rows[0].trigger_type === 'schedule' ? '定时' : '手动' }}</small>
            </h3>
            <el-table :data="group.rows"
              ><el-table-column prop="etl_name" label="任务" min-width="160" /><el-table-column
                label="状态"
                width="100"
                ><template #default="{ row }"
                  ><el-tag
                    :type="
                      row.status === 'success'
                        ? 'success'
                        : row.status === 'running'
                        ? 'warning'
                        : 'danger'
                    "
                    >{{ labels[row.status] }}</el-tag
                  ></template
                ></el-table-column
              ><el-table-column prop="started_at" label="开始" min-width="165" /><el-table-column
                prop="finished_at"
                label="结束"
                min-width="165" /><el-table-column
                prop="row_count"
                label="总行数"
                width="110" /><el-table-column prop="note" label="结果 / 原因" min-width="260"
            /></el-table>
          </section>
          <p class="etl-hint">
            最多显示所选日期内最近 500 条记录。验收失败时，新数据可能已经提交，请根据原因处理。
          </p>
        </el-tab-pane>
        <el-tab-pane label="SQL 版本" name="versions">
          <div class="etl-toolbar">
            <el-button type="primary" :disabled="acting" @click="uploadInput?.click()"
              >上传 SQL 包</el-button
            ><input ref="uploadInput" type="file" accept=".zip" hidden @change="upload" /><span
              >ZIP · UTF-8 · 最大 16 MB；正式包和测试包分别激活</span
            >
          </div>
          <el-table :data="versions"
            ><el-table-column prop="id" label="版本" width="75" /><el-table-column
              label="类型"
              width="80"
              ><template #default="{ row }">{{
                row.package_kind === 'test'
                  ? '测试'
                  : row.package_kind === 'formal'
                  ? '正式'
                  : '未识别'
              }}</template></el-table-column
            ><el-table-column label="状态" width="100"
              ><template #default="{ row }">{{ labels[row.status] }}</template></el-table-column
            ><el-table-column
              prop="source_commit"
              label="来源提交"
              min-width="120"
            /><el-table-column
              prop="file_sha256"
              label="SHA256"
              min-width="180"
              show-overflow-tooltip
            /><el-table-column
              prop="imported_at"
              label="导入时间"
              min-width="170"
            /><el-table-column
              prop="reject_reason"
              label="校验原因"
              min-width="180"
            /><el-table-column label="操作" width="230"
              ><template #default="{ row }"
                ><el-button link type="primary" @click="showFiles(row.id)">查看</el-button
                ><el-button
                  link
                  type="primary"
                  :disabled="acting || row.status === 'rejected' || row.status === 'active'"
                  @click="activate(row)"
                  >激活</el-button
                ><el-button
                  v-if="row.package_kind === 'test'"
                  link
                  type="primary"
                  :disabled="
                    acting ||
                    state.busy ||
                    row.status === 'rejected' ||
                    row.init_status !== 'pending'
                  "
                  @click="initialize(row)"
                  >{{
                    row.init_status === 'pending' ? '初始化' : labels[row.init_status]
                  }}</el-button
                ></template
              ></el-table-column
            ></el-table
          >
        </el-tab-pane>
        <el-tab-pane label="设置" name="settings">
          <el-form :model="form" label-width="140px" class="etl-form"
            ><el-form-item label="每日执行时间"
              ><el-time-select
                v-model="form.dailyAt"
                start="00:00"
                end="23:59"
                step="00:01" /></el-form-item
            ><el-form-item label="整条链启用"
              ><el-switch v-model="form.chainEnabled" /></el-form-item
            ><el-form-item label="刷新专用数据源"
              ><el-select
                v-model="form.refreshDatasourceId"
                filterable
                clearable
                placeholder="选择已有 MySQL 数据源"
                ><el-option
                  v-for="ds in state.datasources"
                  :key="ds.id"
                  :label="ds.name"
                  :value="String(ds.id)" /></el-select></el-form-item
            ><el-form-item
              ><el-button type="primary" :disabled="acting || state.busy" @click="save"
                >保存设置</el-button
              ></el-form-item
            ></el-form
          >
          <div class="etl-switch">
            <h3>正式刷新全局开关</h3>
            <p>本实例：{{ state.instanceId || '未取得实例 ID' }}</p>
            <p>开关绑定实例：{{ state.setting.enabledInstanceId || '未绑定' }}</p>
            <p>{{ state.reason || '本实例生效' }}</p>
            <el-button
              :type="state.setting.schedulerEnabled ? 'danger' : 'primary'"
              :disabled="
                acting ||
                (!state.setting.schedulerEnabled && (state.hardDisabled || !state.instanceId))
              "
              @click="toggleSwitch"
              >{{ state.setting.schedulerEnabled ? '关闭正式刷新' : '启用正式刷新' }}</el-button
            >
          </div>
        </el-tab-pane>
      </el-tabs>
    </template>
    <el-dialog v-model="previewOpen" title="SQL 包 · 只读预览" width="85%"
      ><el-tabs
        ><el-tab-pane v-for="file in preview" :key="file.path" :label="file.path"
          ><p>SHA256：{{ file.sha256 }}</p>
          <pre class="etl-source">{{ file.content }}</pre>
        </el-tab-pane></el-tabs
      ></el-dialog
    >
  </div>
</template>

<style scoped lang="less">
.etl-page {
  height: 100%;
  overflow: auto;
  padding: 24px;
  background: var(--de-bg-color, #fff);
}
.etl-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
  h2 {
    margin: 0 0 8px;
  }
  p {
    margin: 0;
    color: #667085;
  }
}
.etl-status,
.etl-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 16px 0;
  flex-wrap: wrap;
}
.etl-toolbar :deep(.ed-select) {
  width: 200px;
}
.etl-batch {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  margin: 16px 0;
  padding: 16px;
  h3 {
    margin: 0 0 12px;
  }
}
.etl-form {
  max-width: 600px;
  padding-top: 20px;
}
.etl-switch {
  border-top: 1px solid #e5e7eb;
  padding: 16px 0;
}
.etl-hint,
small {
  color: #667085;
}
.etl-source {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 60vh;
  overflow: auto;
  background: #f8fafc;
  padding: 16px;
}
</style>
