<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus-secondary'
import { Codemirror } from 'vue-codemirror'
import { sql } from '@codemirror/lang-sql'
import DataUpdateDrawer from './DataUpdateDrawer.vue'
import { fetchDataUpdates, dataUpdateStatus } from '@/api/company/dataUpdate'
import type { DataUpdateState } from '@/api/company/dataUpdate'
import {
  checkUpdateDdl,
  executeUpdateDdl,
  fetchUpdateDdl,
  reconcileUpdateDdl,
  readUpdateStructure,
  fetchUpdateGroups,
  saveUpdateGroup,
  runUpdateGroup,
  deleteUpdateGroup,
  fetchGroupRuns,
  fetchGroupRun,
  cancelGroupRun,
  reconcileGroupRun
} from '@/api/company/dataUpdateSetup'
import type { DdlOperation, Structure, UpdateGroup, GroupRun } from '@/api/company/dataUpdateSetup'

const props = defineProps<{ datasourceId: string; type: string }>()
const state = ref<DataUpdateState>({
  tasks: [],
  datasources: [],
  instanceId: null,
  hardDisabled: false
})
const groups = ref<UpdateGroup[]>([])
const operations = ref<DdlOperation[]>([])
const loading = ref(false)
const submitting = ref(false)
const drawer = ref<InstanceType<typeof DataUpdateDrawer>>()
const tasks = computed(() =>
  state.value.tasks.filter(task => task.definition.datasourceId === props.datasourceId)
)
const localGroups = computed(() =>
  groups.value.filter(group => group.datasourceId === props.datasourceId)
)
const taskTitle = (id: string) =>
  state.value.tasks.find(task => task.definition.id === id)?.definition.table || id
let generation = 0
async function reload() {
  const token = ++generation
  loading.value = true
  try {
    const [next, nextGroups, nextOps] = await Promise.all([
      fetchDataUpdates(),
      fetchUpdateGroups(),
      fetchUpdateDdl(props.datasourceId)
    ])
    if (token !== generation) return
    state.value = next
    groups.value = nextGroups
    operations.value = nextOps
  } finally {
    if (token === generation) loading.value = false
  }
}
watch(
  () => props.datasourceId,
  () => {
    createVisible.value = false
    groupVisible.value = false
    historyVisible.value = false
    reloadInBackground()
  },
  { immediate: false }
)

const createVisible = ref(false)
const mode = ref('existing')
const table = ref('')
const ddl = ref('')
const operationId = ref('')
const checked = ref('')
const exists = ref(false)
const structure = ref<Structure | null>(null)
const lastOperation = ref<DdlOperation | null>(null)
const extensions = [sql()]
const currentDdl = computed(() => JSON.stringify([props.datasourceId, table.value, ddl.value]))
function newId() {
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6] & 15) | 64
  bytes[8] = (bytes[8] & 63) | 128
  const hex = Array.from(bytes, value => value.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(
    16,
    20
  )}-${hex.slice(20)}`
}
function newTask() {
  table.value = ''
  ddl.value = ''
  checked.value = ''
  exists.value = false
  structure.value = null
  lastOperation.value = null
  operationId.value = newId()
  mode.value = 'existing'
  createVisible.value = true
}
function ddlBody() {
  return {
    operationId: operationId.value,
    datasourceId: props.datasourceId,
    table: table.value.trim(),
    sql: ddl.value
  }
}
async function checkDdl() {
  submitting.value = true
  try {
    const result = await checkUpdateDdl(ddlBody())
    exists.value = result.exists
    checked.value = currentDdl.value
    operationId.value = newId()
    ElMessage.success(result.exists ? '目标表已存在，可读取结构并使用已有表' : '建表 SQL 检查通过')
  } finally {
    submitting.value = false
  }
}
async function createTable() {
  submitting.value = true
  try {
    lastOperation.value = await executeUpdateDdl(ddlBody())
    await reload()
    if (lastOperation.value.status === 'SUCCESS') {
      exists.value = true
      structure.value = await readUpdateStructure(props.datasourceId, table.value.trim())
      ElMessage.success('目标表已创建')
    }
  } finally {
    submitting.value = false
  }
}
async function useTable() {
  if (!table.value.trim()) {
    ElMessage.warning('请填写目标表名')
    return
  }
  submitting.value = true
  try {
    structure.value = await readUpdateStructure(props.datasourceId, table.value.trim())
    createVisible.value = false
    await drawer.value?.open(props.datasourceId, table.value.trim())
  } finally {
    submitting.value = false
  }
}
async function inspectTable() {
  submitting.value = true
  try {
    structure.value = await readUpdateStructure(props.datasourceId, table.value.trim())
  } finally {
    submitting.value = false
  }
}
async function confirmAction(message: string) {
  try {
    await ElMessageBox.confirm(message, '确认操作', {
      confirmButtonText: '确认',
      cancelButtonText: '取消',
      type: 'warning'
    })
    return true
  } catch {
    return false
  }
}
async function reconcileDdl(operation: DdlOperation) {
  if (
    !(await confirmAction(
      '请先确认旧建表执行已结束并核查目标表和索引。确认后读取实际结构并解除占用，SQL 不会重放。'
    ))
  )
    return
  await reconcileUpdateDdl(operation.id)
  await reload()
}

const groupVisible = ref(false)
const groupForm = ref<UpdateGroup>({
  id: null,
  version: 0,
  datasourceId: '',
  name: '',
  tasks: [],
  enabled: false,
  cron: '0 0 3 * * ?',
  timezone: 'Asia/Shanghai',
  owner: null
})
function editGroup(group?: UpdateGroup) {
  groupForm.value = group
    ? structuredClone(group)
    : {
        id: null,
        version: 0,
        datasourceId: props.datasourceId,
        name: '',
        tasks: [],
        enabled: false,
        cron: '0 0 3 * * ?',
        timezone: 'Asia/Shanghai',
        owner: null
      }
  groupVisible.value = true
}
function otherGroup(task: string) {
  return groups.value.some(group => group.id !== groupForm.value.id && group.tasks.includes(task))
}
async function saveGroup() {
  submitting.value = true
  try {
    await saveUpdateGroup(groupForm.value)
    groupVisible.value = false
    await reload()
    ElMessage.success('任务组已保存')
  } finally {
    submitting.value = false
  }
}
async function removeGroup(group: UpdateGroup) {
  if (
    !(await confirmAction('解散任务组后，各任务保留并恢复其独立调度设置。请先核对各任务的开关。'))
  )
    return
  await deleteUpdateGroup(group)
  await reload()
}
const historyVisible = ref(false)
const selectedGroup = ref<UpdateGroup | null>(null)
const businessDate = ref('')
const history = ref<GroupRun[]>([])
const expandedRuns = new Set<string>()
let timer: ReturnType<typeof setTimeout> | undefined
async function refreshHistory() {
  const id = selectedGroup.value?.id
  if (!id) return
  const rows = await fetchGroupRuns(id)
  await Promise.all(
    rows
      .filter(row => expandedRuns.has(row.id))
      .map(async row => {
        row.nodes = (await fetchGroupRun(row.id)).nodes
      })
  )
  if (selectedGroup.value?.id === id) history.value = rows
}
function poll() {
  if (timer) clearTimeout(timer)
  timer = setTimeout(async () => {
    if (!historyVisible.value) return
    try {
      await refreshHistory()
    } catch {
      return
    }
    if (historyVisible.value) poll()
  }, 5000)
}
async function openHistory(group: UpdateGroup) {
  selectedGroup.value = group
  expandedRuns.clear()
  businessDate.value = ''
  historyVisible.value = true
  await refreshHistory()
  poll()
}
async function startGroup() {
  if (!selectedGroup.value) return
  if (!(await confirmAction(`运行「${selectedGroup.value.name}」整组任务。本批各输出分别提交。`)))
    return
  submitting.value = true
  try {
    await runUpdateGroup(selectedGroup.value, businessDate.value)
    await refreshHistory()
  } finally {
    submitting.value = false
  }
}
async function expandRun(row: GroupRun, expanded: GroupRun[]) {
  expandedRuns.clear()
  expanded.forEach(item => expandedRuns.add(item.id))
  if (!expandedRuns.has(row.id)) return
  const detail = await fetchGroupRun(row.id)
  row.nodes = detail.nodes
}
async function cancelRun(row: GroupRun) {
  if (await confirmAction('取消整批后将停止后续节点，已完成的输出保留。')) {
    await cancelGroupRun(row.id)
    await refreshHistory()
  }
}
async function reconcileRun(row: GroupRun) {
  if (
    await confirmAction(
      '请先确认旧执行已结束并核查各目标表。确认后解除本批占用，下次从新批次重新抽取。'
    )
  ) {
    await reconcileGroupRun(row.id)
    await refreshHistory()
  }
}
const statusLabels: Record<string, string> = {
  ...dataUpdateStatus,
  NEEDS_REVIEW: '待核查',
  WAITING: '等待上游',
  BLOCKED: '已阻断',
  REVIEWED: '已核查'
}
const status = (value: string) => statusLabels[value] || value
onBeforeUnmount(() => {
  ++generation
  if (timer) clearTimeout(timer)
})
function reloadInBackground() {
  void reload().catch(() => {
    // The shared HTTP interceptor displays request failures.
  })
}
reloadInBackground()
</script>

<template>
  <div v-loading="loading" class="update-workspace">
    <div class="toolbar">
      <h3>数据更新</h3>
      <el-button @click="reload">刷新</el-button
      ><el-button @click="editGroup()">新建任务组</el-button
      ><el-button type="primary" @click="newTask">新建更新任务</el-button>
    </div>
    <el-table :data="tasks" empty-text="尚未配置更新任务">
      <el-table-column label="目标表"
        ><template #default="{ row }">{{ row.definition.table }}</template></el-table-column
      >
      <el-table-column label="执行方式" width="130"
        ><template #default="{ row }">{{
          row.definition.mode === 'QUERY' ? '查询抽取' : 'SQL 聚合'
        }}</template></el-table-column
      >
      <el-table-column label="调度" width="180"
        ><template #default="{ row }">{{
          row.grouped ? '任务组统一调度' : row.definition.enabled ? '自动调度已开启' : '手动'
        }}</template></el-table-column
      >
      <el-table-column label="操作" width="140"
        ><template #default="{ row }"
          ><el-button text @click="drawer?.open(datasourceId, row.definition.table)"
            >配置 / 记录</el-button
          ></template
        ></el-table-column
      >
      <el-table-column label="最近结果" width="110"
        ><template #default="{ row }">{{
          status(row.latest?.status || '—')
        }}</template></el-table-column
      >
      <el-table-column label="最近成功（UTC）" min-width="180"
        ><template #default="{ row }">{{
          row.latest?.lastSuccessAt || '—'
        }}</template></el-table-column
      >
    </el-table>
    <h3>任务组</h3>
    <p class="hint">
      组内任务每批执行一次，依赖使用本批输入；共享输入在本批结束前保持稳定。加入组后由组统一调度。
    </p>
    <el-table :data="localGroups" empty-text="暂无任务组">
      <el-table-column prop="name" label="名称" /><el-table-column label="任务数" width="90"
        ><template #default="{ row }">{{ row.tasks.length }}</template></el-table-column
      >
      <el-table-column label="自动调度" width="120"
        ><template #default="{ row }">{{
          row.enabled
            ? row.owner === state.instanceId && !state.hardDisabled
              ? '已开启'
              : '当前实例未启用'
            : '关闭'
        }}</template></el-table-column
      >
      <el-table-column label="操作" width="240"
        ><template #default="{ row }"
          ><el-button text @click="editGroup(row)">编辑</el-button
          ><el-button text @click="openHistory(row)">执行 / 批次</el-button
          ><el-button text type="danger" @click="removeGroup(row)">解散</el-button></template
        ></el-table-column
      >
    </el-table>
    <h3>建表操作</h3>
    <el-table :data="operations" empty-text="暂无建表操作">
      <el-table-column prop="table_name" label="目标表" /><el-table-column label="状态" width="120"
        ><template #default="{ row }">{{ status(row.status) }}</template></el-table-column
      >
      <el-table-column prop="completed_steps" label="完成步骤" width="110" /><el-table-column
        prop="message"
        label="结果"
      />
      <el-table-column label="操作" width="150"
        ><template #default="{ row }"
          ><el-button
            v-if="['NEEDS_REVIEW', 'RUNNING'].includes(row.status)"
            text
            @click="reconcileDdl(row)"
            >核查并解除占用</el-button
          ></template
        ></el-table-column
      >
    </el-table>
    <DataUpdateDrawer ref="drawer" @saved="reload" />

    <el-dialog
      v-model="createVisible"
      title="新建更新任务"
      width="min(850px, 96vw)"
      :close-on-click-modal="false"
      :show-close="!submitting"
    >
      <el-form label-position="top" :disabled="submitting">
        <el-form-item label="目标表"
          ><el-input v-model="table" placeholder="填写当前数据源中的表名"
        /></el-form-item>
        <el-radio-group v-model="mode"
          ><el-radio value="existing">使用已有表</el-radio
          ><el-radio v-if="type === 'mysql'" value="create">手写 SQL 建表</el-radio></el-radio-group
        >
        <template v-if="mode === 'create'">
          <p class="hint">
            第一条 CREATE TABLE，后续可添加该表的 CREATE INDEX。建表只执行一次；每日刷新另行配置。
          </p>
          <Codemirror
            v-model="ddl"
            :extensions="extensions"
            :disabled="submitting"
            :style="{ height: '260px' }"
          />
          <el-button :loading="submitting" @click="checkDdl">检查建表 SQL</el-button>
          <el-button
            type="primary"
            :disabled="checked !== currentDdl || exists || submitting"
            @click="createTable"
            >执行建表</el-button
          >
          <el-alert
            v-if="lastOperation"
            :title="lastOperation.message"
            :type="lastOperation.status === 'SUCCESS' ? 'success' : 'warning'"
            :closable="false"
          />
        </template>
        <el-button :loading="submitting" @click="inspectTable">读取实际结构</el-button>
        <el-table v-if="structure" :data="structure.columns" max-height="250"
          ><el-table-column prop="name" label="字段" /><el-table-column
            prop="type"
            label="类型"
          /><el-table-column prop="precision" label="长度 / 精度" /><el-table-column
            prop="defaultValue"
            label="默认值"
          />
          <el-table-column prop="scale" label="小数位" />
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
        <el-table v-if="structure?.indexes.length" :data="structure.indexes" max-height="180">
          <el-table-column prop="name" label="主键 / 索引" /><el-table-column
            prop="column"
            label="字段"
          /><el-table-column prop="position" label="字段顺序" /><el-table-column label="唯一"
            ><template #default="{ row }">{{ row.unique ? '是' : '否' }}</template></el-table-column
          >
        </el-table>
      </el-form>
      <template #footer
        ><el-button type="primary" :loading="submitting" @click="useTable"
          >使用此表，配置刷新</el-button
        ></template
      >
    </el-dialog>
    <el-dialog
      v-model="groupVisible"
      title="任务组"
      width="min(720px, 96vw)"
      :close-on-click-modal="false"
      :show-close="!submitting"
    >
      <el-form label-position="top" :disabled="submitting">
        <el-form-item label="组名"
          ><el-input v-model="groupForm.name" maxlength="100"
        /></el-form-item>
        <el-form-item label="组内任务"
          ><el-select v-model="groupForm.tasks" multiple filterable style="width: 100%"
            ><el-option
              v-for="task in tasks"
              :key="task.definition.id || ''"
              :value="task.definition.id || ''"
              :label="task.definition.table"
              :disabled="otherGroup(task.definition.id || '')" /></el-select
        ></el-form-item>
        <p class="hint">
          在各任务中配置上游；请将完整依赖链一起加入组。执行时固定所有任务版本，失败后阻断后续节点。
        </p>
        <el-form-item label="自动调度"><el-switch v-model="groupForm.enabled" /></el-form-item>
        <el-form-item label="Quartz Cron（秒 分 时 日 月 周）"
          ><el-input v-model="groupForm.cron"
        /></el-form-item>
        <el-form-item label="业务时区"><el-input v-model="groupForm.timezone" /></el-form-item>
      </el-form>
      <template #footer
        ><el-button type="primary" :loading="submitting" @click="saveGroup"
          >保存任务组</el-button
        ></template
      >
    </el-dialog>
    <el-drawer
      v-model="historyVisible"
      :title="selectedGroup?.name || '批次记录'"
      size="min(1000px, 96vw)"
    >
      <div class="toolbar">
        <el-date-picker
          v-model="businessDate"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="业务日期（默认今天）"
        /><el-button type="primary" :loading="submitting" @click="startGroup">运行整组</el-button
        ><el-button @click="refreshHistory">刷新</el-button>
      </div>
      <p class="hint">各输出表分别提交；批次失败时，已成功的输出保留。记录时间为 UTC。</p>
      <el-table :data="history" row-key="id" @expand-change="expandRun">
        <el-table-column type="expand"
          ><template #default="{ row }"
            ><el-table :data="row.nodes || []"
              ><el-table-column label="任务"
                ><template #default="{ row: node }">{{
                  taskTitle(node.task_id)
                }}</template></el-table-column
              ><el-table-column label="状态"
                ><template #default="{ row: node }">{{
                  status(node.status)
                }}</template></el-table-column
              ><el-table-column prop="row_count" label="处理行数" /><el-table-column
                prop="message"
                label="结果" /></el-table></template
        ></el-table-column>
        <el-table-column prop="business_date" label="业务日期" width="120" /><el-table-column
          label="状态"
          width="120"
          ><template #default="{ row }">{{ status(row.status) }}</template></el-table-column
        ><el-table-column prop="message" label="结果" />
        <el-table-column label="操作" width="155"
          ><template #default="{ row }"
            ><el-button
              v-if="['QUEUED', 'RUNNING'].includes(row.status)"
              text
              @click="cancelRun(row)"
              >取消批次</el-button
            ><el-button
              v-if="['NEEDS_REVIEW', 'RUNNING'].includes(row.status)"
              text
              @click="reconcileRun(row)"
              >核查并解除占用</el-button
            ></template
          ></el-table-column
        >
      </el-table>
    </el-drawer>
  </div>
</template>

<style scoped lang="less">
.update-workspace {
  padding: 24px;
  overflow: auto;
  height: 100%;
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
  h3 {
    margin: 0;
    flex: 1;
  }
}
h3 {
  margin-top: 28px;
}
.hint {
  color: #646b78;
  font-size: 12px;
  line-height: 1.8;
}
:deep(.cm-editor) {
  border: 1px solid #dcdfe6;
  margin: 12px 0;
}
</style>
