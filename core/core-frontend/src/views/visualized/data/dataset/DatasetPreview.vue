<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount } from 'vue'
import { cloneDeep } from 'lodash-es'
import { ElMessage } from 'element-plus-secondary'
import {
  queryDatasetPreview,
  exportDatasetData,
  encodePreviewQuery,
  type PreviewQuery,
  type PreviewRule
} from '@/api/dataset'
import { useEmitt } from '@/hooks/web/useEmitt'
import { useAppStoreWithOut } from '@/store/modules/app'
import { useCache } from '@/hooks/web/useCache'
import { matchesField, validateRules, fieldOriginalName } from './previewState'
import DatasetFilterStudio from './DatasetFilterStudio.vue'
import DatasetIcon from './DatasetIcon.vue'

const props = withDefaults(
  defineProps<{
    datasetId?: string | number
    name?: string
    fields?: any[]
    draft?: () => Record<string, any>
    revision?: string | number
    canExport?: boolean
    active?: boolean
  }>(),
  { name: '数据集', fields: () => [], canExport: true, active: true }
)
const emit = defineEmits(['fields'])
const available = ref<any[]>([])
const columns = ref<any[]>([])
const rows = ref<any[]>([])
const rules = ref<PreviewRule[]>([])
const logic = ref<'and' | 'or'>('and')
const selected = ref<string[] | undefined>()
const page = ref(1)
const size = ref(100)
const total = ref<number | null>(null)
const unfilteredTotal = ref<number | null>(null)
const counting = ref(false)
const loading = ref(false)
const error = ref('')
const exporting = ref(false)
let serial = 0
let requested = false
let refreshTimer: ReturnType<typeof setTimeout>
onBeforeUnmount(() => {
  ++serial
  clearTimeout(refreshTimer)
})
const filterOpen = ref(false)
const columnOpen = ref(false)
const pendingColumns = ref<string[]>([])
const columnSearch = ref('')
const visibleFields = computed(() =>
  available.value.filter(field => matchesField(field, columnSearch.value))
)
const detail = ref<string | null>(null)
const initialFilterField = ref('')
const focusedField = ref('')
const fieldSignature = ref('')

const input = (): PreviewQuery => ({
  ...(props.datasetId ? { id: props.datasetId } : {}),
  ...(props.draft ? { draft: props.draft() } : {}),
  rules: cloneDeep(rules.value),
  logic: logic.value,
  ...(selected.value ? { columnIds: [...selected.value] } : {}),
  page: page.value,
  size: size.value
})
const invalid = () => validateRules(rules.value, available.value)
const refresh = async () => {
  requested = true
  clearTimeout(refreshTimer)
  const ticket = ++serial
  const problem = invalid()
  error.value = problem
  if (problem) {
    rows.value = []
    total.value = null
    unfilteredTotal.value = null
    loading.value = false
    counting.value = false
    return
  }
  loading.value = true
  counting.value = true
  total.value = null
  unfilteredTotal.value = null
  rows.value = []
  let query: PreviewQuery
  try {
    query = input()
  } catch (e) {
    error.value = '数据来源尚未准备好'
    loading.value = false
    counting.value = false
    return
  }
  const countJob = Promise.allSettled([
    queryDatasetPreview(query, 'previewCount').then(result => {
      if (ticket !== serial) return
      total.value = Number(result.total)
      if (!query.rules?.length) unfilteredTotal.value = total.value
    }),
    ...(query.rules?.length
      ? [
          queryDatasetPreview({ ...query, rules: [], columnIds: undefined }, 'previewCount').then(
            result => {
              if (ticket === serial) unfilteredTotal.value = Number(result.total)
            }
          )
        ]
      : [])
  ]).finally(() => {
    if (ticket === serial) counting.value = false
  })
  try {
    const result = await queryDatasetPreview(query)
    if (ticket !== serial) return
    columns.value = result.data.fields || []
    rows.value = result.data.data || []
    if (!selected.value) {
      available.value = result.data.fields || []
      emit('fields', available.value)
    }
  } catch (e) {
    if (ticket === serial) error.value = e instanceof Error ? e.message : '预览查询失败'
  } finally {
    if (ticket === serial) loading.value = false
  }
  await countJob
}
const invalidate = () => {
  clearTimeout(refreshTimer)
  ++serial
  loading.value = false
  counting.value = false
  rows.value = []
  total.value = null
  unfilteredTotal.value = null
  filterOpen.value = false
  error.value = '数据来源或字段已修改，请刷新预览'
  if (props.active && (requested || (props.draft && props.fields.length)))
    refreshTimer = setTimeout(() => {
      if (props.active) refresh()
    }, 250)
}
watch(
  () => props.datasetId,
  () => {
    ++serial
    rules.value = []
    selected.value = undefined
    available.value = []
    columns.value = []
    page.value = 1
    filterOpen.value = false
    columnOpen.value = false
    total.value = null
    unfilteredTotal.value = null
    if (!props.draft && props.datasetId) refresh()
  },
  { immediate: true }
)
watch(
  () => props.fields,
  fields => {
    const next = JSON.stringify(fields.map(f => [String(f.id), f.deType, f.name, f.originName]))
    if (fieldSignature.value && fieldSignature.value !== next) invalidate()
    fieldSignature.value = next
    if (props.draft) available.value = fields
  },
  { immediate: true, deep: true }
)
watch(
  () => props.revision,
  (next, previous) => {
    if (previous !== undefined && next !== previous) invalidate()
  }
)
watch(
  () => props.active,
  active => {
    if (active && props.draft && props.fields.length) refresh()
  },
  { flush: 'post', immediate: true }
)

const openFilter = (fieldId = '') => {
  initialFilterField.value = fieldId
  filterOpen.value = true
}
const applyFilter = (next: PreviewRule[], relation: 'and' | 'or') => {
  rules.value = cloneDeep(next)
  logic.value = relation
  page.value = 1
  refresh()
}
const clear = () => {
  rules.value = []
  page.value = 1
  refresh()
}
const removeRule = (index: number) => {
  rules.value.splice(index, 1)
  page.value = 1
  refresh()
}
const ruleLabel = (rule: PreviewRule) => {
  const field = available.value.find(f => String(f.id) === rule.fieldId)
  const labels = {
    in: '包含',
    'not in': '排除',
    eq: '等于',
    not_eq: '不等于',
    between: '范围',
    like: '包含',
    'not like': '不包含',
    gt: '>',
    ge: '≥',
    lt: '<',
    le: '≤',
    null: '为空',
    not_null: '不为空',
    empty: '空字符串',
    not_empty: '非空字符串'
  }
  return [
    field?.name || '字段已失效',
    labels[rule.operator] || rule.operator,
    rule.values
      .slice(0, 2)
      .map(v => (v === '' ? '（空字符串）' : v))
      .join(rule.operator === 'between' ? ' ～ ' : '、'),
    rule.values.length > 2 ? '等' + rule.values.length + '项' : ''
  ]
    .filter(Boolean)
    .join(' ')
}
const focusField = (field: any) => {
  focusedField.value = String(field.id)
  const heading = Array.from(document.querySelectorAll<HTMLElement>('.preview-column-name')).find(
    el => el.dataset.fieldId === String(field.id)
  )
  heading?.scrollIntoView({ block: 'nearest', inline: 'center', behavior: 'smooth' })
}
const openColumns = () => {
  pendingColumns.value = selected.value
    ? [...selected.value]
    : available.value.map(field => String(field.id))
  columnSearch.value = ''
  columnOpen.value = true
}
const selectMatching = (checked: boolean) => {
  const ids = new Set(visibleFields.value.map(field => String(field.id)))
  pendingColumns.value = checked
    ? [...new Set([...pendingColumns.value, ...ids])]
    : pendingColumns.value.filter(id => !ids.has(id))
}
const applyColumns = () => {
  if (!pendingColumns.value.length) {
    ElMessage.error('至少选择一列')
    return
  }
  selected.value = available.value
    .filter(field => pendingColumns.value.includes(String(field.id)))
    .map(field => String(field.id))
  columnOpen.value = false
  page.value = 1
  refresh()
}
const exportData = async (format: string) => {
  if (error.value || loading.value) {
    ElMessage.error('请先刷新并确认预览结果')
    return
  }
  exporting.value = true
  try {
    const app = useAppStoreWithOut()
    const { wsCache } = useCache()
    const embedded = app.getIsDataEaseBi || app.getIsIframe
    const synchronous = embedded && wsCache.get('embeddedExportMode-backend') !== 'async'
    const response = await exportDatasetData({
      id: props.datasetId || undefined,
      filename: props.name,
      format,
      previewJson: JSON.stringify(encodePreviewQuery(input())),
      dataEaseBi: !!embedded
    })
    if (synchronous && response?.data instanceof Blob && !response.data.type.includes('json')) {
      const url = URL.createObjectURL(response.data)
      const link = document.createElement('a')
      link.href = url
      link.download = `${props.name}.${format}`
      link.click()
      setTimeout(() => URL.revokeObjectURL(url), 1000)
      return
    }
    const text = response?.data instanceof Blob ? await response.data.text() : ''
    const body = text ? JSON.parse(text) : response?.data instanceof Blob ? null : response?.data
    // The existing asynchronous endpoint acknowledges a successful task with an empty HTTP 200.
    if (response?.status !== 200 || (body && body.code !== 0)) {
      throw new Error(body?.msg || '导出任务创建失败')
    }
    ElMessage.success('导出任务已创建，可在导出中心下载')
    useEmitt().emitter.emit('data-export-center', { activeName: 'IN_PROGRESS' })
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '导出失败')
  } finally {
    exporting.value = false
  }
}
const copyDetail = async () => {
  try {
    await navigator.clipboard.writeText(detail.value || '')
    ElMessage.success('已复制')
  } catch {
    ElMessage.error('复制失败，请手动选择文本复制')
  }
}
defineExpose({ refresh, invalidate, openFilter, openColumns, exportData, focusField })
</script>

<template>
  <div class="dataset-preview-workspace">
    <div class="preview-toolbar">
      <div class="preview-toolbar-leading">
        <slot name="leading" /><span
          class="preview-count"
          title="匹配行数 / 总行数"
          aria-label="匹配行数 / 总行数"
          >{{ counting ? '…' : total ?? '—' }}/{{ counting ? '…' : unfilteredTotal ?? '—' }}</span
        >
        <div class="preview-pagination">
          <el-pagination
            v-model:current-page="page"
            v-model:page-size="size"
            :total="total ?? (page - 1) * size + rows.length + (rows.length === size ? 1 : 0)"
            :page-sizes="[50, 100, 200, 500]"
            layout="sizes, prev, pager, next"
            :pager-count="5"
            small
            @size-change="
              () => {
                page = 1
                refresh()
              }
            "
            @current-change="refresh"
          />
        </div>
      </div>
      <div class="preview-actions">
        <slot name="actions" />
        <el-button text :disabled="!available.length" @click="openFilter()"
          ><DatasetIcon name="filter" />筛选数据</el-button
        >
        <el-button text :disabled="!available.length" @click="openColumns"
          ><DatasetIcon name="columns" />显示列<small
            >{{ columns.length }}/{{ available.length }}</small
          ></el-button
        >
        <el-button text :loading="loading" title="刷新数据" aria-label="刷新数据" @click="refresh"
          ><DatasetIcon v-if="!loading" name="refresh"
        /></el-button>
        <span v-if="canExport" class="preview-toolbar-divider"></span>
        <el-dropdown v-if="canExport" @command="exportData"
          ><el-button text :loading="exporting"
            ><DatasetIcon name="download" />导出<DatasetIcon name="down" /></el-button
          ><template #dropdown
            ><el-dropdown-menu
              ><el-dropdown-item command="csv">导出 CSV</el-dropdown-item
              ><el-dropdown-item command="xlsx">导出 Excel</el-dropdown-item></el-dropdown-menu
            ></template
          ></el-dropdown
        >
      </div>
    </div>
    <div v-if="rules.length" class="preview-condition-strip">
      <div v-for="(rule, index) in rules" :key="index" class="preview-rule-chip">
        <button :title="ruleLabel(rule)" @click="openFilter(rule.fieldId)">
          {{ ruleLabel(rule) }}</button
        ><button :aria-label="'删除' + ruleLabel(rule)" @click="removeRule(index)">
          <DatasetIcon name="close" />
        </button>
      </div>
      <el-button text class="clear-preview-rules" @click="clear">清空</el-button>
    </div>
    <div v-if="error" class="preview-error" role="status">{{ error }}</div>
    <div class="preview-table">
      <el-table
        v-loading="loading"
        :data="rows"
        height="100%"
        border
        @cell-dblclick="
          (row, column) =>
            (detail = row[column.property] == null ? 'NULL' : String(row[column.property]))
        "
      >
        <el-table-column
          type="index"
          label="#"
          width="52"
          :index="index => (page - 1) * size + index + 1"
        />
        <el-table-column
          v-for="field in columns"
          :key="field.id || field.dataeaseName"
          :prop="field.dataeaseName"
          :label="field.name"
          min-width="180"
          show-overflow-tooltip
        >
          <template #header
            ><div class="preview-heading" :class="{ focused: focusedField === String(field.id) }">
              <div class="preview-column-title">
                <span
                  class="preview-column-name"
                  :data-field-id="String(field.id)"
                  :title="field.name"
                  >{{ field.name }}</span
                ><button
                  class="preview-column-filter"
                  :class="{ active: rules.some(rule => rule.fieldId === String(field.id)) }"
                  :aria-label="'筛选' + field.name"
                  @click.stop="openFilter(String(field.id))"
                >
                  <DatasetIcon name="filter" />
                </button>
              </div>
              <small v-if="fieldOriginalName(field)">{{ fieldOriginalName(field) }}</small>
            </div></template
          >
          <template #default="{ row }"
            ><span :class="{ 'null-value': row[field.dataeaseName] == null }">{{
              row[field.dataeaseName] == null
                ? 'NULL'
                : row[field.dataeaseName] === ''
                ? '（空字符串）'
                : row[field.dataeaseName]
            }}</span></template
          >
        </el-table-column>
        <template #empty>{{ error ? '请更新条件后刷新预览' : '暂无数据' }}</template>
      </el-table>
    </div>

    <DatasetFilterStudio
      v-model="filterOpen"
      :fields="available"
      :rules="rules"
      :logic="logic"
      :query="input"
      :initial-field="initialFilterField"
      :total="unfilteredTotal"
      @apply="applyFilter"
    />

    <el-dialog
      v-model="columnOpen"
      title="设置显示列"
      class="dataset-columns-dialog"
      width="min(700px, 92vw)"
      append-to-body
    >
      <el-input v-model="columnSearch" clearable placeholder="搜索显示名或原始字段名" />
      <div class="filter-controls">
        <el-button text @click="selectMatching(true)">选中匹配字段</el-button
        ><el-button text @click="selectMatching(false)">取消匹配字段</el-button
        ><span>已选 {{ pendingColumns.length }} / {{ available.length }}</span>
      </div>
      <el-checkbox-group v-model="pendingColumns" class="column-options"
        ><el-checkbox v-for="field in visibleFields" :key="field.id" :label="String(field.id)"
          ><span :title="field.originName"
            >{{ field.name
            }}<small v-if="fieldOriginalName(field)" class="column-original-name">{{
              fieldOriginalName(field)
            }}</small></span
          ></el-checkbox
        ></el-checkbox-group
      >
      <template #footer
        ><el-button @click="columnOpen = false">取消</el-button
        ><el-button type="primary" @click="applyColumns">确定</el-button></template
      >
    </el-dialog>

    <el-dialog
      :model-value="detail !== null"
      title="完整值"
      width="min(800px, 92vw)"
      append-to-body
      @close="detail = null"
    >
      <pre class="cell-detail">{{ detail }}</pre>
      <template #footer
        ><el-button @click="copyDetail">复制</el-button
        ><el-button @click="detail = null">关闭</el-button></template
      ></el-dialog
    >
  </div>
</template>

<style scoped lang="less">
.dataset-preview-workspace {
  font-family: var(--de-custom_font, 'PingFang SC'), 'Microsoft YaHei', Arial, sans-serif;
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  min-width: 0;
  background: white;
  border: 0;
  border-radius: 0;
  overflow: hidden;
  color: #35445d;
}
.preview-condition-strip {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 16px;
  min-height: 40px;
  border-bottom: 1px solid #e5eaf1;
  background: #f8f9fc;
  flex-wrap: wrap;
  max-height: 120px;
  overflow: auto;
}
.condition-strip-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: #94a1b5;
  margin-right: 4px;
  .dataset-icon {
    width: 13px;
    height: 13px;
  }
}
.no-preview-rules {
  font-size: 11px;
  color: #9aa6b8;
}
.preview-rule-chip {
  display: flex;
  align-items: center;
  border: 1px solid #dce4ef;
  border-radius: 4px;
  background: white;
  min-width: 0;
  max-width: 320px;
  button {
    border: 0;
    background: none;
    font-family: inherit;
    color: #526887;
    font-size: 11px;
    cursor: pointer;
    padding: 5px 7px;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .dataset-icon {
    width: 11px;
    height: 11px;
  }
}
.add-preview-rule {
  border: 0;
  background: none;
  color: #5b7296;
  font-size: 11px;
  cursor: pointer;
  padding: 3px 6px;
}
.preview-scope-label {
  margin-left: auto;
  color: #a5afbf;
  font-size: 10px;
  white-space: nowrap;
}
.preview-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 16px;
  gap: 12px;
  flex-shrink: 0;
  min-height: 52px;
}
.preview-toolbar-leading {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}
.preview-count {
  color: #98a4b6;
  font-size: 13px;
  font-weight: 600;
  color: #526887;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  strong {
    font-size: 14px;
    color: #405574;
    font-weight: 600;
    margin-right: 4px;
  }
  small {
    margin-left: 8px;
    color: #6e86a4;
    background: #edf2f8;
    border-radius: 3px;
    padding: 2px 5px;
    font-size: 10px;
  }
}
.preview-actions {
  display: flex;
  align-items: center;
  gap: 3px;
  flex-shrink: 0;
  :deep(.ed-button) {
    height: 32px;
    border-radius: 5px;
    font-size: 12px;
    padding: 7px 10px;
    color: #415675;
    margin: 0;
  }
  :deep(.ed-button > span) {
    display: flex;
    align-items: center;
    gap: 6px;
  }
  :deep(.ed-button:hover) {
    background: #edf1f7;
  }
  small {
    font-size: 10px;
    color: #9ba8bd;
  }
  .dataset-icon {
    width: 14px;
    height: 14px;
  }
  .preview-action-count {
    color: white;
    background: #5a7196;
    padding: 1px 4px;
    border-radius: 3px;
  }
}
.preview-toolbar-divider {
  height: 16px;
  width: 1px;
  background: #e5eaf1;
  margin: 0 6px;
}
.preview-table {
  flex: 1;
  min-height: 0;
  min-width: 0;
  border-top: 1px solid #e3e8ef;
  :deep(.ed-table) {
    font-family: inherit;
    --ed-table-header-bg-color: #f5f7fa;
    --ed-table-border-color: #e5eaf2;
    --ed-table-text-color: #435570;
    --ed-table-header-text-color: #667894;
    font-size: 13px;
  }
  :deep(.ed-table .cell) {
    padding: 0 16px;
  }
  :deep(.ed-table th.ed-table__cell) {
    height: 46px;
    font-weight: 500;
    background: #f5f7fa;
  }
  :deep(.ed-table td.ed-table__cell) {
    height: 42px;
    padding: 7px 0;
  }
  :deep(.ed-table__inner-wrapper::before) {
    display: none;
  }
  :deep(.ed-table__body tr:hover > td) {
    background: #f1f5fa;
  }
}
.preview-heading {
  overflow: hidden;
  line-height: 18px;
  &.focused {
    color: #3e5475;
    background: #e8eef7;
    border-radius: 3px;
  }
  small {
    display: flex;
    align-items: center;
    gap: 7px;
    color: #99a7bb;
    font: 10px/18px Consolas, monospace;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    margin-top: 3px;
    span {
      color: #8fa0c0;
      &.numeric {
        color: #6c9e91;
        font-size: 12px;
      }
    }
  }
}
.preview-column-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
}
.preview-column-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.preview-column-filter {
  flex-shrink: 0;
  color: #9caac0;
  background: none;
  border: 0;
  border-radius: 3px;
  padding: 3px;
  cursor: pointer;
  opacity: 0;
  &.active {
    opacity: 1;
    color: #526e97;
  }
  .dataset-icon {
    width: 13px;
    height: 13px;
  }
}
.preview-heading:hover .preview-column-filter,
.preview-column-filter:focus-visible {
  opacity: 1;
}
.null-value {
  color: #a2afc1;
  font-size: 12px;
}
.preview-pagination {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-shrink: 0;
  gap: 12px;
  min-height: 48px;
  padding: 8px 15px;
  border-top: 1px solid #e4eaf2;
  :deep(.ed-pagination) {
    font-size: 12px;
  }
  :deep(.ed-pagination button) {
    border-radius: 4px;
  }
}
.preview-pagination-tip {
  color: #9da9bc;
  font-size: 10px;
}
.preview-error {
  padding: 9px 15px;
  color: #ad7945;
  background: #fff8ec;
  font-size: 12px;
  flex-shrink: 0;
}
.scope-tip,
.filter-controls {
  font-size: 12px;
  color: #8391a6;
}
.filter-controls {
  display: flex;
  gap: 12px;
  align-items: center;
  margin: 12px 0;
}
.column-options {
  display: flex;
  flex-direction: column;
  max-height: 45vh;
  overflow: auto;
  align-items: stretch;
  :deep(.ed-checkbox) {
    height: auto;
    min-height: 44px;
    padding: 6px 9px;
    border-radius: 4px;
    margin-right: 0;
    &:hover {
      background: #f1f4f8;
    }
  }
  :deep(.ed-checkbox__label) {
    font-size: 13px;
    line-height: 20px;
  }
}
.column-original-name {
  display: block;
  color: #a1adbf;
  font: 10px/16px Consolas, monospace;
}
.cell-detail {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 60vh;
  overflow: auto;
  user-select: text;
  line-height: 1.8;
  font-size: 13px;
  color: #43536d;
  background: #f6f8fb;
  border: 1px solid #e5eaf1;
  padding: 16px;
  border-radius: 5px;
}
@media (max-width: 1200px) {
  .preview-scope-label {
    display: none;
  }
  .preview-actions {
    gap: 0;
    :deep(.ed-button) {
      padding: 7px;
    }
  }
  .preview-toolbar {
    padding: 10px;
  }
  .preview-count {
    font-size: 10px;
  }
}
</style>

<style scoped lang="less">
.preview-toolbar-leading {
  gap: 12px;
  flex-wrap: wrap;
}
.preview-pagination {
  min-height: 0;
  border: 0;
  padding: 0;
  gap: 0;
  :deep(.ed-pagination) {
    gap: 3px;
  }
  :deep(.ed-select) {
    width: 100px;
  }
  :deep(.ed-input__wrapper) {
    box-shadow: none;
    background: #f3f5f9;
    border-radius: 6px;
  }
  :deep(.ed-pager li),
  :deep(button) {
    min-width: 26px;
    height: 28px;
    border-radius: 6px;
  }
  :deep(.ed-pager li.is-active) {
    background: #eaf0fa;
    color: #5b70a8;
  }
}
.preview-toolbar {
  flex-wrap: wrap;
  gap: 8px;
  padding-top: 8px;
  padding-bottom: 8px;
}
</style>
