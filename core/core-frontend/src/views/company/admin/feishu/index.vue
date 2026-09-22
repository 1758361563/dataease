<script lang="ts" setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router_2'
import { ElMessage } from 'element-plus-secondary'
import GridTable from '@/components/grid-table/src/GridTable.vue'
import {
  confirmFeishuEmployee,
  createFeishuRecipient,
  createFeishuSubscription,
  disableFeishuRecipient,
  disableFeishuSubscription,
  enableFeishuRecipient,
  enableFeishuSubscription,
  getFeishuIntegration,
  getFeishuRun,
  getFeishuSubscription,
  listFeishuRecipients,
  listFeishuRuns,
  listFeishuSubscriptions,
  runFeishuSubscription,
  searchFeishuEmployees,
  testFeishuIntegration,
  updateFeishuIntegration,
  updateFeishuRecipient,
  updateFeishuSubscription,
  type FeishuDeliveryRun,
  type FeishuEmployeeMatch,
  type FeishuIntegration,
  type FeishuRecipient,
  type FeishuSubscription
} from '@/api/company/feishu'
import { keepStringId, BOOTSTRAP_ADMIN_ID } from '@/utils/companyIds'
import { useCompanyPermissionStoreWithOut } from '@/store/modules/companyPermission'
import {
  acceptedRunNotice,
  isRecipientVerified,
  identityStatusLabel,
  employeeMatchLabel,
  receiveIdTypeFor,
  receiveIdTypeLabel,
  recipientOptionLabel,
  inferExecutionMode,
  inferTargetExecutionMode,
  recipientTypeLabel,
  runStatusLabel,
  targetStatusLabel,
  dataUpdateStatusLabel,
  deliveryLaneLabel,
  subscriptionStatusLabel,
  verifyStatusLabel,
  isIdentityConfirmed,
  type FeishuRecipientType
} from '@/utils/companyFeishu'

const route = useRoute()
const companyStore = useCompanyPermissionStoreWithOut()

const loading = ref(false)
const tab = ref('subscriptions')
const recipients = ref<FeishuRecipient[]>([])
const subscriptions = ref<FeishuSubscription[]>([])
const integration = ref<FeishuIntegration>({ configured: false })
const canEditIntegration = computed(() => keepStringId(companyStore.userId) === BOOTSTRAP_ADMIN_ID)

const recipientDialog = ref(false)
const recipientForm = ref({
  id: '',
  displayName: '',
  recipientType: 'PERSONAL' as FeishuRecipientType,
  feishuId: '',
  status: 'ENABLED'
})

const employeeDialog = ref(false)
const employeeQuery = ref('')
const employeeMatches = ref<FeishuEmployeeMatch[]>([])
const selectedEmployee = ref<FeishuEmployeeMatch | null>(null)
const employeeSearching = ref(false)
const employeeConfirming = ref(false)

const subDrawer = ref(false)
const subForm = ref({
  id: '',
  name: '',
  dashboardId: '',
  chartId: '',
  chartName: '',
  titleTemplate: '',
  bodyTemplate: '',
  includeDetailLink: false,
  fixedFilterJson: '{"version":1,"items":[]}',
  status: 'DRAFT',
  recipientIds: [] as string[],
  scheduleKind: 'manual',
  scheduleTime: '09:00',
  scheduleDayOfWeek: 1,
  scheduleDayOfMonth: 1,
  autoRun: false
})

const runDrawer = ref(false)
const runRows = ref<FeishuDeliveryRun[]>([])
const runDetail = ref<FeishuDeliveryRun | null>(null)
const activeRunId = ref('')
const currentRunSubId = ref('')
const polling = ref(false)

const integrationForm = ref({ appId: '', appSecretRef: 'FEISHU_APP_SECRET', status: 'ENABLED' })

const formatTime = (ms?: number | null) => (ms ? new Date(ms).toLocaleString('zh-CN') : '-')

const runOutcomeLabel = (row: FeishuDeliveryRun) =>
  runStatusLabel(row.status, inferExecutionMode(row))

const targetOutcomeLabel = (row: {
  status: string
  messageId?: string | null
  errorSummary?: string | null
  executionMode?: string
}) =>
  targetStatusLabel(
    row.status,
    inferTargetExecutionMode(row, inferExecutionMode(runDetail.value || undefined))
  )

const parseStoredSchedule = (raw?: string) => {
  try {
    const obj = raw ? JSON.parse(raw) : {}
    return {
      scheduleKind: obj.kind || 'manual',
      scheduleTime: obj.time || '09:00',
      scheduleDayOfWeek: Number(obj.dayOfWeek) || 1,
      scheduleDayOfMonth: Number(obj.dayOfMonth) || 1,
      autoRun: obj.autoRun === true
    }
  } catch {
    return {
      scheduleKind: 'manual',
      scheduleTime: '09:00',
      scheduleDayOfWeek: 1,
      scheduleDayOfMonth: 1,
      autoRun: false
    }
  }
}

const parseFilterJson = (raw: string) => {
  const text = raw.trim() || '{"version":1,"items":[]}'
  return JSON.parse(text)
}

const loadRecipients = async () => {
  recipients.value = await listFeishuRecipients()
}

const loadSubscriptions = async () => {
  subscriptions.value = await listFeishuSubscriptions()
}

const filteredSubscriptions = computed(() => {
  const dashboardId = route.query.dashboardId ? String(route.query.dashboardId) : ''
  const chartId = route.query.chartId ? String(route.query.chartId) : ''
  return subscriptions.value.filter(row => {
    if (dashboardId && keepStringId(row.dashboardId) !== dashboardId) return false
    if (chartId && keepStringId(row.chartId) !== chartId) return false
    return true
  })
})

const loadIntegration = async () => {
  integration.value = await getFeishuIntegration()
  integrationForm.value = {
    appId: integration.value.appId || '',
    appSecretRef: integration.value.appSecretRef || 'FEISHU_APP_SECRET',
    status: integration.value.status || 'ENABLED'
  }
}

const loadAll = async () => {
  loading.value = true
  try {
    await Promise.all([loadRecipients(), loadSubscriptions(), loadIntegration()])
  } finally {
    loading.value = false
  }
}

const openRecipientCreate = () => {
  recipientForm.value = {
    id: '',
    displayName: '',
    recipientType: 'PERSONAL',
    feishuId: '',
    status: 'ENABLED'
  }
  recipientDialog.value = true
}

const openEmployeeCreate = () => {
  employeeQuery.value = ''
  employeeMatches.value = []
  selectedEmployee.value = null
  employeeDialog.value = true
}

const searchEmployees = async () => {
  const query = employeeQuery.value.trim()
  if (!query) {
    ElMessage.warning('请输入姓名')
    return
  }
  employeeSearching.value = true
  selectedEmployee.value = null
  try {
    employeeMatches.value = await searchFeishuEmployees(query)
    if (!employeeMatches.value.length) {
      ElMessage.info('未找到匹配员工，请核对姓名或改用手动填写 User ID')
    }
  } finally {
    employeeSearching.value = false
  }
}

const selectEmployee = (row: FeishuEmployeeMatch) => {
  selectedEmployee.value = row
}

const confirmEmployee = async () => {
  if (!selectedEmployee.value) {
    ElMessage.warning('同名时必须先选择一名员工，不能默认取第一条')
    return
  }
  employeeConfirming.value = true
  try {
    const saved = await confirmFeishuEmployee(selectedEmployee.value.userId)
    if (saved.sendVerified) {
      ElMessage.success('已确认身份。发送验证状态未改，仍以真实发送结果为准')
    } else {
      ElMessage.success('已确认身份并保存为个人收件人，尚未验证消息发送')
    }
    employeeDialog.value = false
    await loadRecipients()
  } finally {
    employeeConfirming.value = false
  }
}

const openRecipientEdit = (row: FeishuRecipient) => {
  recipientForm.value = {
    id: keepStringId(row.id),
    displayName: row.displayName,
    recipientType: row.recipientType,
    feishuId: row.feishuId,
    status: row.status
  }
  recipientDialog.value = true
}

const submitRecipient = async () => {
  if (!recipientForm.value.displayName || !recipientForm.value.feishuId) {
    ElMessage.warning('名称和飞书 ID 不能为空')
    return
  }
  const body = {
    displayName: recipientForm.value.displayName,
    recipientType: recipientForm.value.recipientType,
    receiveIdType: receiveIdTypeFor(recipientForm.value.recipientType),
    feishuId: recipientForm.value.feishuId,
    status: recipientForm.value.status
  }
  if (recipientForm.value.id) {
    await updateFeishuRecipient(recipientForm.value.id, body)
    ElMessage.success('收件人已保存')
  } else {
    await createFeishuRecipient(body)
    ElMessage.success('收件人已创建。手动填写只完成登记，身份未确认，也未验证消息发送')
  }
  recipientDialog.value = false
  await loadRecipients()
}

const toggleRecipient = async (row: FeishuRecipient) => {
  if (row.status === 'ENABLED') {
    await disableFeishuRecipient(keepStringId(row.id))
  } else {
    await enableFeishuRecipient(keepStringId(row.id))
  }
  await loadRecipients()
}

const defaultSubName = (chartName: string) => {
  const base = chartName || '卡片'
  return `${base}-日报`
}

const resetSubForm = (preset?: { dashboardId?: string; chartId?: string; chartName?: string }) => {
  const chartName = preset?.chartName || ''
  subForm.value = {
    id: '',
    name: defaultSubName(chartName),
    dashboardId: preset?.dashboardId || '',
    chartId: preset?.chartId || '',
    chartName,
    titleTemplate: chartName ? `{{cardName}} {{bizDate}}` : '',
    bodyTemplate: '',
    includeDetailLink: false,
    fixedFilterJson: '{"version":1,"items":[]}',
    status: 'DRAFT',
    recipientIds: [],
    scheduleKind: 'manual',
    scheduleTime: '09:00',
    scheduleDayOfWeek: 1,
    scheduleDayOfMonth: 1,
    autoRun: false
  }
}

const openSubCreate = (preset?: { dashboardId?: string; chartId?: string; chartName?: string }) => {
  resetSubForm(preset)
  tab.value = 'subscriptions'
  subDrawer.value = true
}

const openSubEdit = async (row: FeishuSubscription) => {
  const detail = await getFeishuSubscription(keepStringId(row.id))
  subForm.value = {
    id: keepStringId(detail.id),
    name: detail.name,
    dashboardId: keepStringId(detail.dashboardId),
    chartId: keepStringId(detail.chartId),
    chartName: detail.chartName || '',
    titleTemplate: detail.titleTemplate || '',
    bodyTemplate: detail.bodyTemplate || '',
    includeDetailLink: !!detail.includeDetailLink,
    fixedFilterJson: detail.fixedFilterJson || '{"version":1,"items":[]}',
    status: detail.status,
    recipientIds: (detail.recipientIds || []).map(keepStringId),
    ...parseStoredSchedule(detail.scheduleJson)
  }
  tab.value = 'subscriptions'
  subDrawer.value = true
}

const submitSubscription = async () => {
  if (!subForm.value.name || !subForm.value.dashboardId || !subForm.value.chartId) {
    ElMessage.warning('订阅名称、dashboardId、chartId 不能为空')
    return
  }
  if (!subForm.value.recipientIds.length) {
    ElMessage.warning('至少选择一个收件人')
    return
  }
  let fixedFilterJson: Record<string, unknown>
  try {
    fixedFilterJson = parseFilterJson(subForm.value.fixedFilterJson)
  } catch {
    ElMessage.warning('固定筛选必须是合法 JSON 对象')
    return
  }
  const body = {
    name: subForm.value.name,
    dashboardId: subForm.value.dashboardId,
    chartId: subForm.value.chartId,
    chartName: subForm.value.chartName,
    titleTemplate: subForm.value.titleTemplate,
    bodyTemplate: subForm.value.bodyTemplate,
    includeDetailLink: subForm.value.includeDetailLink,
    fixedFilterJson,
    scheduleJson: {
      kind: subForm.value.scheduleKind,
      autoRun: subForm.value.autoRun === true && subForm.value.scheduleKind !== 'manual',
      time: subForm.value.scheduleTime,
      timezone: 'Asia/Shanghai',
      dayOfWeek: subForm.value.scheduleDayOfWeek,
      dayOfMonth: subForm.value.scheduleDayOfMonth
    },
    status: subForm.value.status === 'ENABLED' ? 'ENABLED' : 'DRAFT',
    recipientIds: subForm.value.recipientIds
  }
  if (subForm.value.id) {
    await updateFeishuSubscription(subForm.value.id, body)
    ElMessage.success('订阅已保存')
  } else {
    await createFeishuSubscription(body)
    ElMessage.success('订阅已创建')
  }
  subDrawer.value = false
  await loadSubscriptions()
}

const toggleSubscription = async (row: FeishuSubscription) => {
  const id = keepStringId(row.id)
  if (row.status === 'ENABLED') {
    await disableFeishuSubscription(id)
  } else {
    await enableFeishuSubscription(id)
  }
  await loadSubscriptions()
}

const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms))

const openRuns = async (row: FeishuSubscription, focusRunId?: string) => {
  currentRunSubId.value = keepStringId(row.id)
  runRows.value = await listFeishuRuns(currentRunSubId.value)
  activeRunId.value = focusRunId || (runRows.value[0] ? keepStringId(runRows.value[0].id) : '')
  if (activeRunId.value) {
    runDetail.value = await getFeishuRun(currentRunSubId.value, activeRunId.value)
  } else {
    runDetail.value = null
  }
  runDrawer.value = true
}

const loadRunDetail = async (runId: string) => {
  activeRunId.value = keepStringId(runId)
  if (!currentRunSubId.value || !activeRunId.value) return
  runDetail.value = await getFeishuRun(currentRunSubId.value, activeRunId.value)
}

const pollRun = async (subscriptionId: string, runId: string) => {
  polling.value = true
  try {
    for (let i = 0; i < 40; i++) {
      const detail = await getFeishuRun(subscriptionId, runId)
      runDetail.value = detail
      runRows.value = await listFeishuRuns(subscriptionId)
      const done = ['SUCCEEDED', 'FAILED', 'SKIPPED', 'UNKNOWN'].includes(detail.status)
      if (done) {
        return detail
      }
      await sleep(1500)
    }
    ElMessage.warning('仍在执行中，可稍后在运行记录里刷新')
    return runDetail.value
  } finally {
    polling.value = false
  }
}

const dryRunSubscription = async (row: FeishuSubscription) => {
  if (row.status !== 'ENABLED') {
    ElMessage.warning('只有启用中的订阅可以执行')
    return
  }
  const accepted = await runFeishuSubscription(keepStringId(row.id), { dryRun: true })
  ElMessage.info(acceptedRunNotice(accepted))
  await openRuns(row, keepStringId(accepted.runId || accepted.id))
  await pollRun(keepStringId(row.id), keepStringId(accepted.runId || accepted.id))
}

const saveIntegration = async () => {
  if (!canEditIntegration.value) return
  await updateFeishuIntegration({
    appId: integrationForm.value.appId,
    appSecretRef: integrationForm.value.appSecretRef,
    status: integrationForm.value.status
  })
  ElMessage.success('应用配置已保存（仅写入环境变量名）')
  await loadIntegration()
}

const testIntegration = async () => {
  const result = await testFeishuIntegration()
  ElMessage.success(result.checked || '配置检查完成')
}

const applyRouteQuery = async () => {
  const q = route.query
  if (q.tab === 'recipients' || q.tab === 'integration' || q.tab === 'subscriptions') {
    tab.value = String(q.tab)
  }
  const dashboardId = q.dashboardId ? String(q.dashboardId) : ''
  const chartId = q.chartId ? String(q.chartId) : ''
  const chartName = q.chartName ? String(q.chartName) : ''
  if (q.create === '1') {
    openSubCreate({ dashboardId, chartId, chartName })
    return
  }
  if (dashboardId || chartId) {
    tab.value = 'subscriptions'
  }
}

watch(
  () => route.query,
  () => {
    applyRouteQuery()
  }
)

onMounted(async () => {
  await loadAll()
  await applyRouteQuery()
})
</script>

<template>
  <div class="company-admin-page" v-loading="loading">
    <div class="page-head">
      <span>飞书订阅</span>
      <el-button @click="loadAll">刷新</el-button>
    </div>
    <el-alert
      type="warning"
      :closable="false"
      title="独立服务按 Asia/Shanghai 调度，当前默认模拟发送。测试订阅不会因为切换真实发送而真发。未关联 ETL 的正式订阅可按固定时间执行，页面显示「数据更新状态未经校验」，这不表示数据已更新。已关联任务必须有覆盖约定业务日的成功记录，否则不发送旧图。"
      style="margin-bottom: 12px"
    />
    <el-tabs v-model="tab">
      <el-tab-pane label="订阅" name="subscriptions">
        <div class="toolbar">
          <el-button type="primary" @click="openSubCreate()">新建订阅</el-button>
          <span
            v-if="route.query.dashboardId || route.query.chartId"
            class="hint"
            style="margin: 0 0 0 12px"
          >
            已按当前卡片筛选 dashboardId={{ route.query.dashboardId }} / chartId={{
              route.query.chartId
            }}
          </span>
        </div>
        <GridTable :show-pagination="false" :table-data="filteredSubscriptions" class="admin-grid">
          <el-table-column prop="name" label="名称" min-width="160" />
          <el-table-column prop="chartName" label="卡片" min-width="140" />
          <el-table-column prop="dashboardId" label="dashboardId" min-width="160" />
          <el-table-column prop="chartId" label="chartId" min-width="160" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">{{ subscriptionStatusLabel(row.status) }}</template>
          </el-table-column>
          <el-table-column label="发送隔离" width="90">
            <template #default="{ row }">{{ deliveryLaneLabel(row.deliveryLane) }}</template>
          </el-table-column>
          <el-table-column label="数据更新" min-width="220">
            <template #default="{ row }">{{ dataUpdateStatusLabel(row.freshnessJson) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="340" fixed="right">
            <template #default="{ row }">
              <el-button text @click="openSubEdit(row)">编辑</el-button>
              <el-button text @click="toggleSubscription(row)">
                {{ row.status === 'ENABLED' ? '停用' : '启用' }}
              </el-button>
              <el-button text @click="dryRunSubscription(row)">试运行</el-button>
              <el-button text @click="openRuns(row)">运行记录</el-button>
            </template>
          </el-table-column>
        </GridTable>
      </el-tab-pane>

      <el-tab-pane label="收件人" name="recipients">
        <div class="toolbar">
          <el-button type="primary" @click="openEmployeeCreate">添加员工</el-button>
          <el-button @click="openRecipientCreate">手动填写 User ID / 群聊</el-button>
        </div>
        <p class="hint">
          添加员工会按姓名搜索通讯录并确认身份，自动保存可用于机器人私聊的 User
          ID。搜索成功不等于消息已发送。群聊管理不变，仍用手动入口填写 chat_id。不会把飞书部门映射成
          BI 用户组，也不要求对方有 BI 账号。
        </p>
        <GridTable :show-pagination="false" :table-data="recipients" class="admin-grid">
          <el-table-column prop="displayName" label="名称" min-width="140" />
          <el-table-column label="类型" width="90">
            <template #default="{ row }">{{ recipientTypeLabel(row.recipientType) }}</template>
          </el-table-column>
          <el-table-column label="ID 类型" width="140">
            <template #default="{ row }">{{ receiveIdTypeLabel(row.receiveIdType) }}</template>
          </el-table-column>
          <el-table-column prop="feishuId" label="飞书 ID" min-width="180" />
          <el-table-column label="身份" width="110">
            <template #default="{ row }">
              <el-tag :type="isIdentityConfirmed(row.identityStatus) ? 'success' : 'info'">
                {{ identityStatusLabel(row.identityStatus) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="发送验证" width="120">
            <template #default="{ row }">
              <el-tag :type="isRecipientVerified(row.verifyStatus) ? 'success' : 'info'">
                {{ verifyStatusLabel(row.verifyStatus) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">{{ row.status === 'ENABLED' ? '启用' : '停用' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="180" fixed="right">
            <template #default="{ row }">
              <el-button text @click="openRecipientEdit(row)">编辑</el-button>
              <el-button text @click="toggleRecipient(row)">
                {{ row.status === 'ENABLED' ? '停用' : '启用' }}
              </el-button>
            </template>
          </el-table-column>
        </GridTable>
      </el-tab-pane>

      <el-tab-pane label="应用配置" name="integration">
        <p class="hint">仅系统管理员 id=1 可修改。页面只读写环境变量名，不会回显 App Secret。</p>
        <el-form label-width="140px" style="max-width: 560px">
          <el-form-item label="状态">
            {{ integration.configured ? integration.status : '未配置' }}
            · Secret 引用 {{ integration.hasSecretRef ? '已设置' : '未设置' }}
          </el-form-item>
          <el-form-item label="App ID">
            <el-input v-model="integrationForm.appId" :disabled="!canEditIntegration" />
          </el-form-item>
          <el-form-item label="App Secret 引用">
            <el-input v-model="integrationForm.appSecretRef" :disabled="!canEditIntegration" />
          </el-form-item>
          <el-form-item label="上次检查">
            {{ formatTime(integration.lastVerifiedAt) }}
          </el-form-item>
          <el-form-item>
            <el-button v-if="canEditIntegration" type="primary" @click="saveIntegration"
              >保存</el-button
            >
            <el-button v-if="canEditIntegration" @click="testIntegration">检查配置</el-button>
            <span v-if="!canEditIntegration" class="hint">当前账号只能查看脱敏状态。</span>
          </el-form-item>
        </el-form>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="employeeDialog" title="添加员工" width="640px">
      <p class="hint">
        输入姓名后查看匹配结果。同名员工会列出部门与 User ID
        尾号，必须点选确认，不会自动取第一条。确认后只保存身份，不会发送消息。
      </p>
      <el-form label-width="80px" @submit.prevent="searchEmployees">
        <el-form-item label="姓名" required>
          <div class="employee-search">
            <el-input
              v-model="employeeQuery"
              placeholder="请输入员工姓名"
              @keyup.enter="searchEmployees"
            />
            <el-button type="primary" :loading="employeeSearching" @click="searchEmployees"
              >搜索</el-button
            >
          </div>
        </el-form-item>
      </el-form>
      <el-table
        :data="employeeMatches"
        highlight-current-row
        max-height="320"
        @current-change="selectEmployee"
      >
        <el-table-column prop="name" label="姓名" min-width="120" />
        <el-table-column label="部门" min-width="180">
          <template #default="{ row }">{{
            (row.departments || []).join(' / ') || '部门未知'
          }}</template>
        </el-table-column>
        <el-table-column label="User ID 尾号" width="120">
          <template #default="{ row }">…{{ row.userIdTail }}</template>
        </el-table-column>
      </el-table>
      <p class="hint" v-if="selectedEmployee">已选择：{{ employeeMatchLabel(selectedEmployee) }}</p>
      <p class="hint" v-else-if="employeeMatches.length">请点选一名员工后再确认身份。</p>
      <template #footer>
        <el-button @click="employeeDialog = false">取消</el-button>
        <el-button
          type="primary"
          :disabled="!selectedEmployee"
          :loading="employeeConfirming"
          @click="confirmEmployee"
        >
          确认身份
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="recipientDialog"
      :title="recipientForm.id ? '编辑收件人' : '手动填写 User ID / 群聊'"
      width="520px"
    >
      <el-form label-width="120px">
        <el-form-item label="显示名" required>
          <el-input v-model="recipientForm.displayName" />
        </el-form-item>
        <el-form-item label="对象类型" required>
          <el-select v-model="recipientForm.recipientType">
            <el-option label="个人" value="PERSONAL" />
            <el-option label="群聊" value="CHAT" />
          </el-select>
        </el-form-item>
        <el-form-item label="ID 类型">
          <el-input :model-value="receiveIdTypeFor(recipientForm.recipientType)" disabled />
        </el-form-item>
        <el-form-item label="飞书 ID" required>
          <el-input
            v-model="recipientForm.feishuId"
            :placeholder="receiveIdTypeFor(recipientForm.recipientType)"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="recipientDialog = false">取消</el-button>
        <el-button type="primary" @click="submitRecipient">保存</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="subDrawer" :title="subForm.id ? '编辑订阅' : '创建订阅'" size="560px">
      <el-form label-width="120px">
        <el-form-item label="订阅名称" required>
          <el-input v-model="subForm.name" />
        </el-form-item>
        <el-form-item label="dashboardId" required>
          <el-input v-model="subForm.dashboardId" />
        </el-form-item>
        <el-form-item label="chartId" required>
          <el-input v-model="subForm.chartId" />
        </el-form-item>
        <el-form-item label="卡片名称">
          <el-input v-model="subForm.chartName" />
        </el-form-item>
        <el-form-item label="消息标题">
          <el-input v-model="subForm.titleTemplate" />
        </el-form-item>
        <el-form-item label="正文">
          <el-input v-model="subForm.bodyTemplate" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="固定筛选 JSON">
          <el-input v-model="subForm.fixedFilterJson" type="textarea" :rows="4" />
        </el-form-item>
        <el-form-item label="收件人" required>
          <el-select v-model="subForm.recipientIds" multiple filterable style="width: 100%">
            <el-option
              v-for="item in recipients.filter(r => r.status === 'ENABLED')"
              :key="keepStringId(item.id)"
              :label="recipientOptionLabel(item)"
              :value="keepStringId(item.id)"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="调度">
          <el-select v-model="subForm.scheduleKind" style="width: 100%">
            <el-option label="仅手动" value="manual" />
            <el-option label="每天" value="daily" />
            <el-option label="每周" value="weekly" />
            <el-option label="每月" value="monthly" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="subForm.scheduleKind !== 'manual'" label="时间">
          <el-input v-model="subForm.scheduleTime" placeholder="HH:mm" />
        </el-form-item>
        <el-form-item v-if="subForm.scheduleKind === 'weekly'" label="星期">
          <el-select v-model="subForm.scheduleDayOfWeek" style="width: 100%">
            <el-option label="周一" :value="1" />
            <el-option label="周二" :value="2" />
            <el-option label="周三" :value="3" />
            <el-option label="周四" :value="4" />
            <el-option label="周五" :value="5" />
            <el-option label="周六" :value="6" />
            <el-option label="周日" :value="7" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="subForm.scheduleKind === 'monthly'" label="日期">
          <el-input v-model.number="subForm.scheduleDayOfMonth" />
        </el-form-item>
        <el-form-item v-if="subForm.scheduleKind !== 'manual'" label="自动触发">
          <el-switch v-model="subForm.autoRun" />
          <span class="hint" style="margin-left: 8px">本机 dry-run，不会真实发送</span>
        </el-form-item>
        <el-form-item>
          <span class="hint">时区固定 Asia/Shanghai。同一计划时刻只会生成一条 SCHEDULED run。</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="subDrawer = false">取消</el-button>
        <el-button type="primary" @click="submitSubscription">保存</el-button>
      </template>
    </el-drawer>

    <el-drawer v-model="runDrawer" title="运行记录" size="640px">
      <p class="hint" v-if="polling">正在查询执行状态…</p>
      <GridTable :show-pagination="false" :table-data="runRows" class="admin-grid">
        <el-table-column prop="runId" label="runId" min-width="160">
          <template #default="{ row }">{{ row.runId || row.id }}</template>
        </el-table-column>
        <el-table-column label="状态" width="170">
          <template #default="{ row }">{{ runOutcomeLabel(row) }}</template>
        </el-table-column>
        <el-table-column label="开始" min-width="160">
          <template #default="{ row }">{{ formatTime(row.startedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button text @click="loadRunDetail(row.runId || row.id)">展开</el-button>
          </template>
        </el-table-column>
      </GridTable>
      <div v-if="runDetail" class="run-detail">
        <h4>run {{ runDetail.runId || runDetail.id }} · {{ runOutcomeLabel(runDetail) }}</h4>
        <p class="hint">{{ runDetail.errorSummary || '无汇总错误' }}</p>
        <GridTable :show-pagination="false" :table-data="runDetail.targets || []">
          <el-table-column prop="displayName" label="收件人" min-width="140" />
          <el-table-column label="类型" width="80">
            <template #default="{ row }">{{ recipientTypeLabel(row.recipientType) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="150">
            <template #default="{ row }">{{ targetOutcomeLabel(row) }}</template>
          </el-table-column>
          <el-table-column prop="messageId" label="messageId" min-width="160" />
          <el-table-column prop="errorSummary" label="错误" min-width="160" />
        </GridTable>
      </div>
    </el-drawer>
  </div>
</template>

<style lang="less" scoped>
.company-admin-page {
  padding: 16px 24px;
}
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 20px;
  font-weight: 500;
  margin-bottom: 16px;
}
.toolbar {
  margin-bottom: 12px;
}
.hint {
  color: #646a73;
  font-size: 12px;
  margin: 0 0 12px;
}
.employee-search {
  display: flex;
  gap: 8px;
  width: 100%;
}
.run-detail {
  margin-top: 16px;
}
</style>
