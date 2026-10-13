<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus-secondary'
import { dvMainStoreWithOut } from '@/store/modules/data-visualization/dvMain'
import { snapshotStoreWithOut } from '@/store/modules/data-visualization/snapshot'
import { useEmitt } from '@/hooks/web/useEmitt'
import {
  calculationRuntime,
  validateCalculationConfig
} from '@/api/visualization/dataVisualization'
import { getFieldByDQ } from '@/api/chart'
import {
  applyDashboardParameters,
  dashboardParameterRuntime as runtime,
  displayDashboardExpression,
  emptyDashboardCalculation,
  hasDashboardQueryButton,
  initializeDashboardParameters
} from '@/utils/dashboardParameterRuntime'
import type { DashboardCalculationConfig } from '@/models/dashboardCalculation'
import { previewRelativeDate } from '@/utils/dashboardRelativeDate'
import Icon from '@/components/icon-custom/src/Icon.vue'
import chevronUp from '@/assets/svg/icon_down_outlined.svg'

const props = defineProps<{
  editable?: boolean
  collapsible?: boolean
  components?: any[]
  info?: any
  views?: Record<string, any>
}>()
const store = dvMainStoreWithOut()
const { emitter } = useEmitt()
const macroExamples = [
  { expression: '{{{sunday last week}}}', label: '上周日' },
  { expression: '{{{monday last week}}}', label: '上周一' },
  { expression: '{{{yesterday %yyyy-MM-01}}}', label: '昨日所在月份的月初' }
]
const drawer = ref(false)
const collapsed = ref(false)
const saving = ref(false)
const definition = ref<DashboardCalculationConfig>(emptyDashboardCalculation())
const availableFields = ref<any[]>([])
const activeDataset = ref('')
const activeField = ref(-1)
const activeTab = ref('parameters')
const extraChart = ref<any>(null)
const info = computed(() => props.info || store.dvInfo)
const buttons = computed(() =>
  hasDashboardQueryButton(
    props.components || store.componentData,
    props.views || store.canvasViewInfo
  )
)
const hasParameters = computed(
  () => runtime.sceneId === String(info.value.id) && runtime.config.parameters.length > 0
)
const dirty = computed(() => JSON.stringify(runtime.draft) !== JSON.stringify(runtime.applied))
const datasets = computed(() => {
  const unique = new Map<string, string>()
  if (extraChart.value?.tableId)
    unique.set(
      String(extraChart.value.tableId),
      `${extraChart.value.title || '当前卡片'} 使用的数据集`
    )
  Object.values(store.canvasViewInfo).forEach((chart: any) => {
    if (chart.tableId) unique.set(String(chart.tableId), `${chart.title || '卡片'} 使用的数据集`)
  })
  definition.value.datasetFields.forEach(field => {
    if (!unique.has(field.datasetGroupId))
      unique.set(field.datasetGroupId, `数据集 ${field.datasetGroupId}`)
  })
  return Array.from(unique, ([id, name]) => ({ id, name }))
})

const refresh = () => {
  Object.keys(props.views || store.canvasViewInfo).forEach(id => emitter.emit(`query-data-${id}`))
}
const apply = () => {
  try {
    applyDashboardParameters()
    refresh()
    return true
  } catch (error) {
    ElMessage.error(error.message)
    return false
  }
}
const changed = () => {
  if (!buttons.value) apply()
}
const clear = () => {
  runtime.draft = { ...runtime.defaults }
  changed()
}
const open = () => {
  if (!info.value.id) {
    ElMessage.info('请先保存仪表板')
    return
  }
  definition.value = JSON.parse(
    info.value.calculationConfig || JSON.stringify(emptyDashboardCalculation())
  )
  definition.value.datasetFields.forEach(field => {
    field.expression = displayDashboardExpression(field.expression, definition.value)
  })
  drawer.value = true
}
const addParameter = () => {
  definition.value.parameters.push({
    name: '',
    type: 'DATE',
    required: true,
    default: { mode: 'FIXED', value: '' }
  })
}
const addField = () => {
  definition.value.datasetFields.push({
    name: '',
    datasetGroupId: datasets.value[0]?.id || '',
    groupType: 'd',
    deType: 2,
    expression: ''
  })
}
const removeField = (index: number) => {
  definition.value.datasetFields.splice(index, 1)
  activeField.value = -1
}
const changeDefault = (parameter, mode) => {
  parameter.default =
    mode === 'FIXED'
      ? { mode, value: '' }
      : mode === 'RELATIVE'
      ? { mode, relative: { base: 'TODAY', period: 'MONTH', offset: 0, position: 'START' } }
      : { mode, expression: '{{{sunday last week}}}' }
}
const periods = [
  { value: 'DAY', label: '日', start: '当天', end: '当天' },
  { value: 'WEEK', label: '周', start: '周初（周一）', end: '周末（周日）' },
  { value: 'MONTH', label: '月', start: '月初', end: '月末' },
  { value: 'QUARTER', label: '季度', start: '季初', end: '季末' },
  { value: 'YEAR', label: '年', start: '年初', end: '年末' }
]
const relativePreview = rule => {
  try {
    return previewRelativeDate(rule, definition.value.timezone)
  } catch (error) {
    return error.message
  }
}
const relativePosition = rule => periods.find(period => period.value === rule.period)
const setRelativeOffset = (rule, offset) => {
  rule.offset = offset === 'CUSTOM' ? -2 : offset
}
const selectField = async (index: number) => {
  activeField.value = index
  activeDataset.value = definition.value.datasetFields[index]?.datasetGroupId || ''
  availableFields.value = []
  const chart: any =
    Object.values(store.canvasViewInfo).find(
      (item: any) => String(item.tableId) === activeDataset.value
    ) || (String(extraChart.value?.tableId) === activeDataset.value ? extraChart.value : null)
  if (!chart) return
  const fields = await getFieldByDQ(activeDataset.value, chart.id, {
    sceneId: info.value.id,
    resourceTable: 'snapshot'
  })
  availableFields.value = [...fields.dimensionList, ...fields.quotaList].filter(
    field => field.id !== '-1'
  )
}
const insert = (token: string) => {
  const field = definition.value.datasetFields[activeField.value]
  if (field) field.expression += token
}
const save = async () => {
  saving.value = true
  try {
    const normalized = (
      await validateCalculationConfig(String(info.value.id), definition.value, store.canvasViewInfo)
    ).data
    const config = JSON.stringify(normalized)
    const resolved = (
      await calculationRuntime(info.value.id, 'snapshot', config, store.canvasViewInfo)
    ).data
    const previous = Object.fromEntries(
      Object.entries(runtime.applied).filter(([id, value]) => value !== runtime.defaults[id])
    )
    store.dvInfo.calculationConfig = config
    initializeDashboardParameters(info.value.id, 'snapshot', resolved)
    Object.keys(runtime.applied).forEach(id => {
      if (Object.prototype.hasOwnProperty.call(previous, id)) {
        runtime.applied[id] = previous[id]
        runtime.draft[id] = previous[id]
      }
    })
    drawer.value = false
    const snapshots = snapshotStoreWithOut()
    snapshots.snapshotDisableTime = 0
    snapshots.recordSnapshotCache('calcData')
    snapshots.recordSnapshot()
    emitter.emit('dashboard-calculation-changed')
    refresh()
    ElMessage.success('配置已应用，保存仪表板后保留')
  } finally {
    saving.value = false
  }
}

watch(buttons, (value, previous) => {
  if (!value && previous && dirty.value) apply()
})
watch(
  () => [info.value.calculationConfig, info.value.id, info.value.dataState],
  async ([config]) => {
    if (!props.editable || !info.value.id) return
    const definitions = JSON.parse(config || JSON.stringify(emptyDashboardCalculation()))
    if (
      runtime.sceneId === String(info.value.id) &&
      JSON.stringify(definitions) === JSON.stringify(runtime.config)
    )
      return
    const sceneId = info.value.id
    const resolved = (
      await calculationRuntime(
        sceneId,
        'snapshot',
        JSON.stringify(definitions),
        store.canvasViewInfo
      )
    ).data
    if (info.value.id !== sceneId || info.value.calculationConfig !== config) return
    initializeDashboardParameters(sceneId, 'snapshot', resolved)
    emitter.emit('dashboard-calculation-changed')
    refresh()
  }
)
useEmitt({
  name: 'dashboard-parameter-settings-open',
  callback: () => {
    if (props.editable) {
      activeTab.value = 'parameters'
      open()
    }
  }
})
useEmitt({
  name: 'dashboard-shared-field-edit',
  callback: () => {
    if (props.editable) {
      open()
      activeTab.value = 'fields'
    }
  }
})
useEmitt({
  name: 'dashboard-shared-field-add',
  callback: chart => {
    if (!props.editable) return
    extraChart.value = { ...chart }
    open()
    activeTab.value = 'fields'
    definition.value.datasetFields.push({
      name: '',
      datasetGroupId: String(chart.tableId),
      groupType: 'd',
      deType: 2,
      expression: ''
    })
    selectField(definition.value.datasetFields.length - 1)
  }
})
</script>

<template>
  <div v-if="hasParameters" class="dashboard-parameter-bar" @mousedown.stop>
    <template v-if="!collapsible || !collapsed">
      <label
        v-for="parameter in runtime.config.parameters"
        :key="parameter.id"
        class="parameter-control"
      >
        <span>{{ parameter.name }}</span>
        <el-date-picker
          v-model="runtime.draft[parameter.id]"
          type="date"
          value-format="YYYY-MM-DD"
          format="YYYY-MM-DD"
          :clearable="!parameter.required"
          @change="changed"
        />
      </label>
      <el-button @click="clear">重置</el-button>
      <span v-if="buttons && dirty" class="pending">点击筛选器的查询按钮后生效</span>
    </template>
    <span v-else class="field-hint"
      >参数 · {{ runtime.config.parameters.length }} 项<span v-if="dirty"> · 待查询</span></span
    >
    <el-tooltip
      v-if="collapsible"
      :content="collapsed ? '展开参数' : '收起参数'"
      placement="bottom"
    >
      <el-button
        text
        circle
        class="collapse-parameters"
        :aria-label="collapsed ? '展开参数' : '收起参数'"
        :aria-expanded="!collapsed"
        @click="collapsed = !collapsed"
      >
        <Icon><chevronUp class="svg-icon" :class="{ 'chevron-down': collapsed }" /></Icon>
      </el-button>
    </el-tooltip>
  </div>
  <el-drawer
    v-model="drawer"
    title="全局参数与共享计算字段"
    size="min(840px, 100vw)"
    class="dashboard-calculation-drawer"
    append-to-body
  >
    <el-tabs v-model="activeTab" class="calculation-tabs">
      <el-tab-pane label="日期参数" name="parameters">
        <p class="section-description">参数供本仪表板的计算公式引用，无需绑定图表字段。</p>
        <div v-if="!definition.parameters.length" class="empty-state">
          <strong>还没有日期参数</strong>
          <p>添加期初、期末等参数，设置固定日期或相对日期作为默认值。</p>
        </div>
        <div
          v-for="(parameter, index) in definition.parameters"
          :key="parameter.id || index"
          class="definition-card"
        >
          <div class="card-heading">
            <strong>日期参数 {{ index + 1 }}</strong>
            <el-button text type="danger" @click="definition.parameters.splice(index, 1)"
              >删除</el-button
            >
          </div>
          <div class="form-grid">
            <div class="form-item">
              <span class="form-label">参数名称</span>
              <el-input v-model="parameter.name" placeholder="例如：期初" />
            </div>
            <div class="form-item">
              <span class="form-label">默认值方式</span>
              <el-select
                :model-value="parameter.default?.mode || 'FIXED'"
                @change="mode => changeDefault(parameter, mode)"
              >
                <el-option label="固定日期" value="FIXED" />
                <el-option label="相对日期" value="RELATIVE" /><el-option
                  label="时间宏（高级）"
                  value="MACRO"
                />
              </el-select>
            </div>
            <div class="form-item full-width">
              <span class="form-label">{{
                parameter.default?.mode === 'FIXED'
                  ? '默认日期'
                  : parameter.default?.mode === 'RELATIVE'
                  ? '相对日期规则'
                  : '时间宏表达式'
              }}</span>
              <el-date-picker
                v-if="parameter.default?.mode === 'FIXED'"
                v-model="parameter.default.value"
                type="date"
                value-format="YYYY-MM-DD"
                format="YYYY-MM-DD"
                placeholder="选择默认日期"
                class="default-date"
              />
              <div v-else-if="parameter.default?.mode === 'RELATIVE'" class="relative-rule">
                <div class="relative-controls">
                  <el-select
                    :model-value="
                      [-1, 0, 1].includes(parameter.default.relative.offset)
                        ? parameter.default.relative.offset
                        : 'CUSTOM'
                    "
                    @change="value => setRelativeOffset(parameter.default.relative, value)"
                    aria-label="周期偏移"
                  >
                    <el-option label="当前" :value="0" /><el-option
                      label="上一"
                      :value="-1"
                    /><el-option label="下一" :value="1" /><el-option
                      label="自定义偏移"
                      value="CUSTOM"
                    />
                  </el-select>
                  <el-select v-model="parameter.default.relative.period" aria-label="日期周期">
                    <el-option
                      v-for="period in periods"
                      :key="period.value"
                      :value="period.value"
                      :label="period.label"
                    />
                  </el-select>
                  <el-select
                    v-if="parameter.default.relative.period !== 'DAY'"
                    v-model="parameter.default.relative.position"
                    aria-label="周期位置"
                  >
                    <el-option
                      :label="relativePosition(parameter.default.relative)?.start"
                      value="START"
                    />
                    <el-option
                      :label="relativePosition(parameter.default.relative)?.end"
                      value="END"
                    />
                  </el-select>
                </div>
                <div
                  v-if="![-1, 0, 1].includes(parameter.default.relative.offset)"
                  class="custom-offset"
                >
                  <span class="form-label">偏移周期数</span>
                  <el-input-number
                    v-model="parameter.default.relative.offset"
                    :min="-1200"
                    :max="1200"
                    :step="1"
                    :precision="0"
                    aria-label="偏移周期数"
                  />
                  <span class="field-hint">负数往前，正数往后。</span>
                </div>
                <div class="relative-preview">
                  默认日期：<strong>{{ relativePreview(parameter.default.relative) }}</strong>
                </div>
                <details class="relative-advanced">
                  <summary>
                    高级：基准日期（{{
                      parameter.default.relative.base === 'YESTERDAY' ? '昨天' : '今天'
                    }}）
                  </summary>
                  <el-select v-model="parameter.default.relative.base" aria-label="基准日期">
                    <el-option label="今天" value="TODAY" /><el-option
                      label="昨天"
                      value="YESTERDAY"
                    />
                  </el-select>
                  <p class="field-hint">
                    以昨天为基准时，每月 1 日的「当前月 · 月初」会得到上个月月初。
                  </p>
                </details>
              </div>
              <el-input
                v-else-if="parameter.default?.mode === 'MACRO'"
                v-model="parameter.default.expression"
                placeholder="{{{sunday last week}}}"
              />
              <p class="field-hint">
                {{
                  parameter.default?.mode === 'FIXED'
                    ? '保存后，每次打开仪表板以此日期作为默认值。'
                    : '保存日期规则，打开仪表板时根据当时的日期计算默认值。'
                }}
              </p>
            </div>
          </div>
          <el-checkbox v-model="parameter.required">必填</el-checkbox>
        </div>
        <div class="section-actions">
          <el-button @click="addParameter">＋ 添加日期参数</el-button>
        </div>
        <div
          v-if="definition.parameters.some(parameter => parameter.default?.mode === 'MACRO')"
          class="macro-help"
        >
          <strong>时间宏示例</strong>
          <div v-for="example in macroExamples" :key="example.expression" class="macro-example">
            <code>{{ example.expression }}</code
            ><span>{{ example.label }}</span>
          </div>
        </div>
      </el-tab-pane>
      <el-tab-pane label="共享计算字段" name="fields">
        <p class="section-description">同仪表板内使用相同数据集的卡片共同使用这些字段。</p>
        <div v-if="!definition.datasetFields.length" class="empty-state">
          <strong>还没有共享计算字段</strong>
          <p>
            {{
              datasets.length
                ? '添加一个计算字段，在公式中引用日期参数。'
                : '请先在仪表板中添加图表并选择数据集，再创建共享计算字段。'
            }}
          </p>
        </div>
        <div
          v-for="(field, index) in definition.datasetFields"
          :key="field.id || index"
          class="definition-card"
          @click="activeField = index"
        >
          <div class="card-heading">
            <strong>共享计算字段 {{ index + 1 }}</strong>
            <el-button text type="danger" @click.stop="removeField(index)">删除</el-button>
          </div>
          <div class="form-grid">
            <div class="form-item">
              <span class="form-label">字段名称</span>
              <el-input v-model="field.name" placeholder="例如：YTD期间" />
            </div>
            <div class="form-item">
              <span class="form-label">所属数据集</span>
              <el-select
                v-model="field.datasetGroupId"
                placeholder="数据集"
                @change="selectField(index)"
              >
                <el-option
                  v-for="dataset in datasets"
                  :key="dataset.id"
                  :label="dataset.name"
                  :value="dataset.id"
                />
              </el-select>
            </div>
            <div class="form-item">
              <span class="form-label">字段角色</span>
              <el-select v-model="field.groupType"
                ><el-option label="维度" value="d" /><el-option label="指标" value="q"
              /></el-select>
            </div>
            <div class="form-item">
              <span class="form-label">数据类型</span>
              <el-select v-model="field.deType"
                ><el-option label="整数" :value="2" /><el-option
                  label="小数"
                  :value="3" /><el-option label="文本" :value="0" /><el-option
                  label="日期"
                  :value="1"
              /></el-select>
            </div>
            <div class="form-item full-width">
              <span class="form-label">计算公式</span>
              <el-input
                v-model="field.expression"
                type="textarea"
                :rows="4"
                placeholder="if(and([取值时间]>=[DYNAMIC_PARAMS.期初],[取值时间]<=[DYNAMIC_PARAMS.期末]),1,0)"
                @focus="selectField(index)"
              />
            </div>
          </div>
        </div>
        <div class="section-actions">
          <el-button :disabled="!datasets.length" @click="addField">＋ 添加共享计算字段</el-button>
        </div>
        <div v-if="activeField >= 0 && definition.datasetFields[activeField]" class="insert-panel">
          <p class="field-hint">
            点击插入到「{{ definition.datasetFields[activeField].name || '当前字段' }}」的公式末尾
          </p>
          <div class="tokens">
            <el-button
              v-for="parameter in definition.parameters"
              :key="parameter.id || parameter.name"
              size="small"
              @click="insert(`[DYNAMIC_PARAMS.${parameter.name}]`)"
              >{{ parameter.name }}</el-button
            >
            <el-button
              v-for="field in availableFields"
              :key="field.id"
              size="small"
              @click="insert(`[${field.id}]`)"
              >{{ field.name }}</el-button
            >
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>
    <template #footer>
      <div class="drawer-footer">
        <span class="field-hint">应用后，请保存仪表板以保留配置。</span>
        <div>
          <el-button @click="drawer = false">取消</el-button
          ><el-button type="primary" :loading="saving" @click="save">应用配置</el-button>
        </div>
      </div>
    </template>
  </el-drawer>
</template>

<style scoped lang="less">
.dashboard-parameter-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  padding: 10px 16px;
  background: var(--el-bg-color);
  border-bottom: 1px solid var(--el-border-color);
}
.parameter-control {
  display: flex;
  align-items: center;
  gap: 8px;
}
.collapse-parameters {
  margin-left: auto;
  flex-shrink: 0;
  :deep(svg) {
    width: 16px;
    height: 16px;
  }
}
.chevron-down {
  transform: rotate(180deg);
}
.parameter-control :deep(:is(.el-date-editor, .ed-date-editor)) {
  width: 180px;
  flex-shrink: 0;
}
.dashboard-calculation-drawer {
  :deep(:is(.el-drawer__header, .ed-drawer__header)) {
    margin-bottom: 0;
    padding: 24px;
    border-bottom: 1px solid var(--el-border-color-lighter, #ebeef5);
  }
  :deep(:is(.el-drawer__body, .ed-drawer__body)) {
    padding: 20px 24px;
  }
  :deep(:is(.el-drawer__footer, .ed-drawer__footer)) {
    padding: 16px 24px;
    border-top: 1px solid var(--el-border-color-lighter, #ebeef5);
  }
}
.calculation-tabs :deep(:is(.el-tabs__header, .ed-tabs__header)) {
  margin-bottom: 24px;
}
.section-description {
  margin: 0 0 20px;
  color: var(--el-text-color-secondary, #606266);
  font-size: 13px;
  line-height: 1.7;
}
.definition-card {
  padding: 20px;
  margin-bottom: 16px;
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
  border-radius: 12px;
  background: var(--el-bg-color, #fff);
}
.card-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  font-size: 14px;
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px 20px;
  margin-bottom: 14px;
}
.form-item {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
  :deep(:is(.el-select, .ed-select)),
  :deep(:is(.el-input, .ed-input)) {
    width: 100%;
  }
  :deep(.default-date) {
    width: 240px;
    max-width: 100%;
    flex-shrink: 0;
  }
  :deep(:is(.el-textarea__inner, .ed-textarea__inner)) {
    line-height: 1.7;
  }
}
.form-label {
  font-size: 13px;
  font-weight: 500;
}
.full-width {
  grid-column: 1 / -1;
}
.relative-controls {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}
.relative-preview {
  margin-top: 14px;
  padding: 12px 14px;
  border-radius: 8px;
  background: var(--el-fill-color-light, #f5f7fa);
  font-size: 13px;
}
.relative-advanced {
  margin-top: 14px;
  font-size: 13px;
  summary {
    cursor: pointer;
    color: var(--el-text-color-secondary, #606266);
    margin-bottom: 12px;
  }
  :deep(:is(.el-select, .ed-select)) {
    max-width: 240px;
    margin-bottom: 8px;
  }
}
.custom-offset {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  align-items: center;
  margin-top: 12px;
}
.section-actions {
  margin: 20px 0;
}
.empty-state {
  padding: 32px 24px;
  text-align: center;
  background: var(--el-fill-color-light, #f5f7fa);
  border-radius: 12px;
  font-size: 14px;
  p {
    margin: 10px 0 0;
    line-height: 1.7;
    color: var(--el-text-color-secondary, #606266);
  }
}
.macro-help,
.insert-panel {
  padding: 16px 20px;
  background: var(--el-fill-color-light, #f5f7fa);
  border-radius: 10px;
  font-size: 13px;
  line-height: 1.7;
}
.macro-help > strong {
  display: block;
  margin-bottom: 10px;
}
.macro-example {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 16px;
  margin-top: 8px;
  code {
    overflow-wrap: anywhere;
    color: var(--el-color-primary, #3370ff);
  }
  span {
    color: var(--el-text-color-secondary, #606266);
  }
}
.drawer-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}
.tokens {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 14px;
  :deep(:is(.el-button, .ed-button) + :is(.el-button, .ed-button)) {
    margin-left: 0;
  }
}
.pending,
.field-hint {
  margin: 0;
  color: var(--el-text-color-secondary, #606266);
  font-size: 12px;
  line-height: 1.7;
}
@media (max-width: 560px) {
  .relative-controls {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    > :last-child:nth-child(3) {
      grid-column: 1 / -1;
    }
  }
  .form-grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .definition-card {
    padding: 16px;
  }
  .dashboard-calculation-drawer :deep(:is(.el-drawer__body, .ed-drawer__body)) {
    padding: 16px;
  }
}
</style>
