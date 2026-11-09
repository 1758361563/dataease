<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount } from 'vue'
import { fetchUpdateSchedule, dataUpdateStatus } from '@/api/company/dataUpdate'
import type { DataUpdateState, UpdateSchedulePlan } from '@/api/company/dataUpdate'
import type { UpdateGroup } from '@/api/company/dataUpdateSetup'
import { formatBeijingTime } from '@/utils/beijingTime'
import AdminHelp from '@/views/company/admin/AdminHelp.vue'
import { scheduleRows, scheduleStrategy } from './updateSchedule'
import type { ScheduleRow } from './updateSchedule'

const props = defineProps<{ state: DataUpdateState; groups: UpdateGroup[]; datasourceId: string }>()
const emit = defineEmits<{
  configure: [datasource: string, table: string]
  group: [group: UpdateGroup]
}>()
const date = ref(formatBeijingTime(Date.now()).slice(0, 10))
const search = ref('')
const type = ref('')
const scope = ref('current')
const page = ref(1)
const loading = ref(false)
const error = ref(false)
const selected = ref<ScheduleRow | null>(null)
const detail = ref(false)
const plans = ref<{ tasks: UpdateSchedulePlan[]; groups: UpdateSchedulePlan[] }>({
  tasks: [],
  groups: []
})
let generation = 0
async function reload() {
  const token = ++generation
  loading.value = true
  error.value = false
  try {
    const result = await fetchUpdateSchedule(date.value)
    if (token === generation) plans.value = result
  } catch {
    if (token === generation) {
      error.value = true
      plans.value = { tasks: [], groups: [] }
    }
  } finally {
    if (token === generation) loading.value = false
  }
}
watch(
  [date, () => props.state, () => props.groups],
  () => {
    detail.value = false
    void reload()
  },
  { immediate: true }
)
onBeforeUnmount(() => {
  generation++
})
const sourceName = (id: string) =>
  props.state.datasources.find(source => source.id === id)?.name || id
const modeName = (mode: string) =>
  ({ FEISHU: '飞书', QUERY: '查询抽取', SCRIPT: 'SQL' }[mode] || mode)
const rows = computed(() =>
  scheduleRows(props.state.tasks, props.groups, plans.value).filter(row => {
    const d = row.task.definition
    return (
      (scope.value === 'all' || d.datasourceId === props.datasourceId) &&
      (!type.value || type.value === d.mode) &&
      `${d.table} ${row.group?.name || ''} ${sourceName(d.datasourceId)}`
        .toLowerCase()
        .includes(search.value.trim().toLowerCase())
    )
  })
)
const visible = computed(() => rows.value.slice((page.value - 1) * 50, page.value * 50))
const taskCount = computed(() => new Set(rows.value.map(row => row.task.definition.id)).size)
const timeCount = computed(() => new Set(rows.value.map(row => row.time)).size)
const truncated = computed(() =>
  [...plans.value.tasks, ...plans.value.groups].some(plan => plan.times.truncated)
)
watch([rows, search, type, scope], () => {
  page.value = 1
})
function open(row: ScheduleRow) {
  selected.value = row
  detail.value = true
}
function configureSelected() {
  if (!selected.value) return
  detail.value = false
  emit(
    'configure',
    selected.value.task.definition.datasourceId,
    selected.value.task.definition.table
  )
}
function configureGroup() {
  if (!selected.value?.group) return
  detail.value = false
  emit('group', selected.value.group)
}
const dependencyNames = (row: ScheduleRow) =>
  row.task.definition.dependencies
    .map(id => props.state.tasks.find(task => task.definition.id === id)?.definition.table || id)
    .join('、')
</script>

<template>
  <section v-loading="loading" class="schedule-panel">
    <div class="schedule-tools">
      <el-date-picker
        v-model="date"
        type="date"
        value-format="YYYY-MM-DD"
        :clearable="false"
        aria-label="计划日期"
      />
      <span class="timezone">北京时间</span>
      <AdminHelp
        content="按当前配置计算所选日期的计划，不是历史运行记录。同一时点表示同时到期；实际启动受排队影响。任务组成员由组统一调度，依赖任务须等待上游成功。"
      />
      <div class="spacer" />
      <el-input
        v-model="search"
        clearable
        placeholder="搜索目标表或任务组"
        class="schedule-search"
      />
      <el-select v-model="type" aria-label="来源类型"
        ><el-option label="全部来源" value="" /><el-option label="飞书" value="FEISHU" /><el-option
          label="查询抽取"
          value="QUERY" /><el-option label="SQL" value="SCRIPT"
      /></el-select>
      <el-select v-model="scope" aria-label="数据源范围"
        ><el-option label="当前数据源" value="current" /><el-option label="全部数据源" value="all"
      /></el-select>
      <el-button :loading="loading" @click="reload">刷新计划</el-button>
    </div>
    <div class="schedule-summary">
      <span
        ><b>{{ taskCount }}</b> 张表</span
      ><span
        ><b>{{ timeCount }}</b> 个启动时点</span
      ><span
        ><b>{{ rows.length }}</b> 条计划</span
      ><span class="summary-note">已开启的定时计划</span>
    </div>
    <el-alert v-if="error" type="error" title="计划加载失败，请刷新重试" :closable="false" />
    <el-alert
      v-if="truncated"
      type="warning"
      title="存在高频计划，每个调度最多展示当日前 1000 次触发"
      :closable="false"
    />
    <el-table
      :data="visible"
      row-key="key"
      :max-height="620"
      empty-text="所选日期没有匹配的定时计划"
      @row-click="open"
    >
      <el-table-column label="计划时间" width="122"
        ><template #default="{ row }"
          ><span class="plan-time">{{ formatBeijingTime(row.time).slice(11) }}</span
          ><span v-if="row.dependent" class="cell-note">上游完成后</span></template
        ></el-table-column
      >
      <el-table-column label="目标表" min-width="230"
        ><template #default="{ row }"
          ><button class="table-link" @click.stop="open(row)">
            {{ row.dependent ? '↳ ' : '' }}{{ row.task.definition.table }}</button
          ><span class="cell-note">{{
            row.group ? `任务组 · ${row.group.name}` : sourceName(row.task.definition.datasourceId)
          }}</span></template
        ></el-table-column
      >
      <el-table-column label="来源类型" width="115"
        ><template #default="{ row }"
          ><span class="source-tag" :class="{ feishu: row.task.definition.mode === 'FEISHU' }">{{
            modeName(row.task.definition.mode)
          }}</span></template
        ></el-table-column
      >
      <el-table-column label="更新策略" min-width="155"
        ><template #default="{ row }">{{ scheduleStrategy(row.task) }}</template></el-table-column
      >
      <el-table-column label="调度状态" width="150"
        ><template #default="{ row }"
          ><span :class="row.effective ? 'enabled' : 'inactive'">{{
            row.effective ? '当前实例已启用' : '当前实例未启用'
          }}</span></template
        ></el-table-column
      >
      <el-table-column label="最近结果" width="100"
        ><template #default="{ row }">{{
          dataUpdateStatus[row.task.latest?.status || ''] || row.task.latest?.status || '—'
        }}</template></el-table-column
      >
      <el-table-column label="最近成功" width="175"
        ><template #default="{ row }">{{
          formatBeijingTime(row.task.latest?.lastSuccessAt)
        }}</template></el-table-column
      >
    </el-table>
    <div class="schedule-footer">
      <span>依赖行的时间表示所属计划时点，实际执行需等待上游。</span
      ><el-pagination
        v-if="rows.length > 50"
        v-model:current-page="page"
        :total="rows.length"
        :page-size="50"
        layout="prev, pager, next"
      />
    </div>
    <el-drawer v-model="detail" title="计划详情" size="440px" append-to-body class="update-surface">
      <template v-if="selected">
        <h3 class="detail-title">{{ selected.task.definition.table }}</h3>
        <el-descriptions :column="1">
          <el-descriptions-item label="目标数据源">{{
            sourceName(selected.task.definition.datasourceId)
          }}</el-descriptions-item>
          <el-descriptions-item label="计划时点"
            >{{ formatBeijingTime(selected.time) }}（北京时间）</el-descriptions-item
          >
          <el-descriptions-item label="更新策略">{{
            scheduleStrategy(selected.task)
          }}</el-descriptions-item>
          <el-descriptions-item label="触发方式">{{
            selected.group ? '任务组统一调度' : selected.dependent ? '上游依赖' : '独立定时'
          }}</el-descriptions-item>
          <el-descriptions-item v-if="!selected.dependent || selected.group" label="Cron">{{
            selected.group?.cron || selected.task.definition.cron
          }}</el-descriptions-item>
          <el-descriptions-item label="配置时区">{{
            selected.group?.timezone || selected.task.definition.timezone
          }}</el-descriptions-item>
          <el-descriptions-item v-if="selected.group" label="任务组">{{
            selected.group.name
          }}</el-descriptions-item>
          <el-descriptions-item
            v-if="selected.task.definition.dependencies.length"
            label="上游任务"
            >{{ dependencyNames(selected) }}</el-descriptions-item
          >
          <el-descriptions-item label="调度状态">{{
            selected.effective ? '当前实例已启用' : '当前实例未启用'
          }}</el-descriptions-item>
        </el-descriptions>
      </template>
      <template #footer
        ><el-button v-if="selected?.group" @click="configureGroup">配置任务组</el-button
        ><el-button v-if="selected" type="primary" @click="configureSelected"
          >配置 / 运行记录</el-button
        ></template
      >
    </el-drawer>
  </section>
</template>

<style scoped lang="less">
.schedule-panel {
  border: 1px solid #e4e8ef;
  border-radius: 10px;
  overflow: hidden;
  background: white;
}
.schedule-tools {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px 18px;
  flex-wrap: wrap;
}
.schedule-tools :deep(.ed-date-editor) {
  width: 155px;
  flex: none;
}
.schedule-tools .ed-select {
  width: 135px;
}
.schedule-search {
  width: 210px;
}
.spacer {
  flex: 1;
}
.timezone,
.schedule-footer,
.schedule-summary {
  color: #8a94a6;
  font-size: 12px;
}
.schedule-summary {
  display: flex;
  gap: 24px;
  border-top: 1px solid #edf0f5;
  padding: 13px 18px;
}
.schedule-summary b {
  color: #3f4e69;
  margin-right: 4px;
  font-weight: 600;
}
.summary-note {
  margin-left: auto;
}
.plan-time {
  font-weight: 600;
  color: #6077ad;
  font-variant-numeric: tabular-nums;
}
.cell-note {
  display: block;
  color: #929caf;
  font-size: 11px;
  margin-top: 4px;
}
.table-link {
  border: none;
  padding: 0;
  background: transparent;
  color: #35435d;
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.table-link:hover {
  color: #6077ad;
}
.source-tag {
  background: #f0f3f9;
  color: #76839b;
  padding: 3px 8px;
  border-radius: 4px;
  font-size: 12px;
}
.source-tag.feishu {
  background: #eff6f2;
  color: #648c79;
}
.enabled {
  color: #69917f;
  font-size: 12px;
}
.inactive {
  color: #a68451;
  font-size: 12px;
}
.schedule-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 18px;
}
.detail-title {
  font-size: 17px;
  margin: 0 0 24px;
  overflow-wrap: anywhere;
}
:deep(.ed-table__row) {
  cursor: pointer;
}
:deep(.ed-table__cell) {
  padding: 13px 0;
}
:deep(.ed-table th.ed-table__cell) {
  background: #fafbfe;
  font-size: 12px;
  color: #8793a6;
  font-weight: 500;
}
</style>
