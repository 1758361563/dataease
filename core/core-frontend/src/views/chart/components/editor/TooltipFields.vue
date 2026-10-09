<script setup lang="ts">
import { computed, ref } from 'vue'
import { cloneDeep } from 'lodash-es'
import { ElMessage } from 'element-plus-secondary'
import { guid } from '@/views/visualized/data/dataset/form/util'
import { formatterItem } from '@/views/chart/components/js/formatter'
import { findTooltipField } from '@/views/chart/components/js/tooltipFieldIdentity'
import {
  editableTooltipFields,
  tooltipAxes,
  tooltipDimensions,
  tooltipContentKey,
  tooltipQueryFields
} from '@/views/chart/components/js/tooltipContent'
import iconDelete from '@/assets/svg/icon_delete-trash_outlined.svg'
import iconEdit from '@/assets/svg/icon_edit_outlined.svg'
import iconSummary from '@/assets/svg/icon_functions_outlined.svg'
import { fieldType } from '@/utils/attr'
import { iconFieldMap } from '@/components/icon-group/field-list'
import ValueFormatterEdit from './drag-item/components/ValueFormatterEdit.vue'

const props = defineProps<{
  chart: ChartObj
  fields: Axis[]
  draggingFields: Axis[]
  themes: string
}>()
const emit = defineEmits(['change'])
const rows = computed(() => editableTooltipFields(props.chart))
const dragging = ref<number>()
const editing = ref<any>()
const editingKey = ref('')
const editingMode = ref('')
const titles = { name: '编辑显示名称', format: '数值格式', summary: '汇总方式' }
const fieldSummaryOptions = field =>
  String(field.id) === '-1'
    ? ['count']
    : [2, 3].includes(field.deType)
    ? ['sum', 'avg', 'min', 'max', 'count', 'count_distinct']
    : ['count', 'count_distinct']
const unavailable = field => field.tooltipSource === 'unavailable' || field.tooltipUnavailable
function publish(fields, requestData = false) {
  const tooltip = {
    ...cloneDeep(props.chart.customAttr.tooltip),
    contentMode: 'explicit',
    autoAxisKeys: [
      ...new Set([
        ...(props.chart.customAttr.tooltip.autoAxisKeys || []),
        ...tooltipAxes(props.chart).map(tooltipContentKey)
      ])
    ],
    seriesTooltipFormatter: fields
  }
  emit('change', { tooltip, extTooltip: tooltipQueryFields(fields), requestData })
}
function addFields(fields) {
  const next = cloneDeep(rows.value)
  for (const source of fields) {
    let field: any = cloneDeep(source)
    if (field.groupType === 'd') {
      const dimension = tooltipDimensions(props.chart).find(
        item => String(item.id) === String(field.id)
      )
      if (!dimension) {
        ElMessage.warning('提示中的维度需先用于图表分组，避免改变汇总结果。')
        continue
      }
      field = cloneDeep(dimension)
    } else {
      const axes = tooltipAxes(props.chart).filter(item => String(item.id) === String(field.id))
      const unused = axes.find(
        axis =>
          !findTooltipField(
            next.filter(item => item.tooltipSource === 'axis'),
            axis,
            axis.axisType
          )
      )
      if (unused) field = cloneDeep(unused)
      else {
        field.tooltipSource = 'extra'
        field.fieldInstanceId = String(guid())
        delete field.metricInstanceId
        delete field.seriesId
        delete field.axisType
        field.sort = 'none'
        field.summary ||= [2, 3].includes(field.deType) ? 'sum' : 'count'
      }
    }
    field.show = true
    field.formatterCfg ||= cloneDeep(formatterItem)
    if (field.tooltipSource !== 'axis') field.tooltipItemId = String(guid())
    next.push(field)
  }
  publish(next, true)
}
function startDrag(event: DragEvent, index: number) {
  dragging.value = index
  event.dataTransfer?.setData(
    'application/x-dataease-tooltip',
    tooltipContentKey(rows.value[index])
  )
}
function drop(event: DragEvent, target = rows.value.length) {
  event.preventDefault()
  event.stopPropagation()
  if (dragging.value === undefined) {
    let fields = props.draggingFields
    if (!fields.length) {
      try {
        const raw = event.dataTransfer?.getData('quota') || event.dataTransfer?.getData('dimension')
        const payload = raw ? JSON.parse(raw) : []
        fields = Array.isArray(payload)
          ? payload
              .filter(field => String(field.datasetId) === String(props.chart.tableId))
              .map(field => props.fields.find(source => String(source.id) === String(field.id)))
              .filter(Boolean)
          : []
      } catch {
        fields = []
      }
    }
    if (fields.length) addFields(fields)
    return
  }
  const next = cloneDeep(rows.value)
  const from = dragging.value
  const [field] = next.splice(from, 1)
  next.splice(target > from ? target - 1 : target, 0, field)
  dragging.value = undefined
  publish(next)
}
function command(action, field, index) {
  const next = cloneDeep(rows.value)
  if (action === 'remove') {
    next.splice(index, 1)
    publish(next, true)
    return
  }
  if (action === 'inherit') {
    next[index].tooltipFormatOverride = false
    next[index].tooltipNameOverride = false
    const source = props.fields.find(item => String(item.id) === String(field.id))
    next[index].formatterCfg = cloneDeep(source?.formatterCfg || formatterItem)
    if (field.tooltipSource !== 'axis') next[index].chartShowName = source?.chartShowName
    publish(next)
    return
  }
  editingKey.value = tooltipContentKey(field)
  editing.value = cloneDeep(field)
  editing.value.formatterCfg ||= cloneDeep(formatterItem)
  editingMode.value = action
}
function save() {
  const next = cloneDeep(rows.value)
  const index = next.findIndex(field => tooltipContentKey(field) === editingKey.value)
  if (index < 0) {
    editing.value = undefined
    return
  }
  if (editingMode.value === 'format') {
    const decimals = editing.value.formatterCfg.decimalCount
    if (!Number.isInteger(decimals) || decimals < 0 || decimals > 10) {
      ElMessage.error('小数位数需为 0 到 10 的整数。')
      return
    }
    editing.value.tooltipFormatOverride = true
  }
  if (editingMode.value === 'name') editing.value.tooltipNameOverride = true
  next[index] = cloneDeep(editing.value)
  publish(next, editingMode.value === 'summary')
  editing.value = undefined
}
</script>

<template>
  <section class="tooltip-fields" :class="{ dark: themes === 'dark' }" aria-label="提示字段">
    <div class="field-heading">
      <span>提示 / 字段</span>
      <el-tooltip content="清空提示字段" :effect="themes" placement="top">
        <button
          class="clear-fields"
          aria-label="清空提示字段"
          :disabled="!rows.length"
          @click="publish([], true)"
        >
          <el-icon :size="14"><iconDelete class="svg-icon" /></el-icon>
        </button>
      </el-tooltip>
    </div>
    <div class="field-drop" @dragover.prevent @drop="drop($event)">
      <div
        v-for="(field, index) in rows"
        :key="tooltipContentKey(field)"
        class="field-row"
        draggable="true"
        @dragstart.stop="startDrag($event, index)"
        @dragend="dragging = undefined"
        @dragover.prevent
        @drop="drop($event, index)"
      >
        <el-dropdown
          :effect="themes"
          trigger="click"
          @command="action => command(action, field, index)"
        >
          <el-tag
            class="field-tag"
            :class="{ dimension: field.tooltipSource === 'dimension', invalid: unavailable(field) }"
          >
            <el-icon class="field-icon"
              ><component :is="iconFieldMap[fieldType[field.deType]]"
            /></el-icon>
            <span class="field-name" :title="field.chartShowName || field.name">{{
              field.chartShowName || field.name
            }}</span>
            <span v-if="unavailable(field)" class="field-summary">（已失效）</span>
            <span
              v-else-if="field.summary && field.tooltipSource !== 'dimension'"
              class="field-summary"
              >（{{ $t('chart.' + field.summary) }}）</span
            >
            <el-icon class="field-arrow"><iconDown /></el-icon>
          </el-tag>
          <template #dropdown>
            <el-dropdown-menu
              class="drop-style"
              :class="{ 'dark-dimension-quota': themes === 'dark' }"
            >
              <el-dropdown-item
                v-if="field.tooltipSource === 'extra' && !field.agg && !field.isAgg"
                class="menu-item-padding"
                command="summary"
              >
                <el-icon><iconSummary class="svg-icon" /></el-icon><span>汇总方式</span>
              </el-dropdown-item>
              <el-dropdown-item
                v-if="field.tooltipSource !== 'dimension'"
                class="menu-item-padding"
                command="format"
                :disabled="unavailable(field)"
              >
                <el-icon /><span>数值格式</span>
              </el-dropdown-item>
              <el-dropdown-item
                class="menu-item-padding"
                command="name"
                :disabled="unavailable(field)"
              >
                <el-icon><iconEdit class="svg-icon" /></el-icon><span>编辑显示名称</span>
              </el-dropdown-item>
              <el-dropdown-item
                v-if="field.tooltipSource !== 'dimension'"
                class="menu-item-padding"
                command="inherit"
                :disabled="unavailable(field)"
              >
                <el-icon /><span>恢复默认名称和格式</span>
              </el-dropdown-item>
              <el-dropdown-item class="menu-item-padding" command="remove">
                <el-icon><iconDelete class="svg-icon" /></el-icon><span>删除</span>
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
      <span v-if="!rows.length" class="placeholder">拖动字段至此处</span>
    </div>
    <el-dialog
      v-if="editing"
      :model-value="true"
      :title="titles[editingMode]"
      width="420px"
      append-to-body
      @close="editing = undefined"
    >
      <el-input
        v-if="editingMode === 'name'"
        v-model="editing.chartShowName"
        :placeholder="editing.name"
        maxlength="50"
        aria-label="显示名称"
      />
      <value-formatter-edit
        v-if="editingMode === 'format'"
        :formatter-item="editing"
        :chart="chart"
      />
      <el-select v-if="editingMode === 'summary'" v-model="editing.summary" aria-label="汇总方式">
        <el-option
          v-for="summary in fieldSummaryOptions(editing)"
          :key="summary"
          :value="summary"
          :label="$t('chart.' + summary)"
        />
      </el-select>
      <template #footer
        ><el-button @click="editing = undefined">取消</el-button
        ><el-button type="primary" @click="save">确定</el-button></template
      >
    </el-dialog>
  </section>
</template>

<style scoped lang="less">
.tooltip-fields {
  width: 100%;
  font-size: 12px;
}
.field-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 400;
}
.clear-fields {
  border: 0;
  background: transparent;
  color: #646a73;
  cursor: pointer;
  padding: 0 2px;
  height: 18px;
}
.clear-fields:disabled {
  opacity: 0.4;
  cursor: default;
}
.field-drop {
  margin-top: 8px;
  padding: 2px 0 0;
  min-height: 32px;
  border: 1px dashed #bbbfc4;
  border-radius: 6px;
  background: rgba(31, 35, 41, 0.05);
}
.field-drop:has(.field-row) {
  background: transparent;
}
.field-row {
  padding: 0 2px;
}
.field-row :deep(.ed-dropdown) {
  width: 100%;
  display: block;
}
.field-tag {
  padding: 1px 8px;
  margin-bottom: 3px;
  height: 28px;
  line-height: 28px;
  border-radius: 6px;
  width: 100%;
  border: 1px solid #04b49c;
  background: #04b49c0a;
  color: #1f2329;
  cursor: grab;
}
.field-tag :deep(.ed-tag__content) {
  display: flex;
  align-items: center;
  width: 100%;
  min-width: 0;
  gap: 4px;
}
.field-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.field-summary {
  white-space: nowrap;
}
.field-icon {
  color: #04b49c;
  flex-shrink: 0;
}
.dimension {
  border-color: #3370ff;
  background: #3370ff0a;
}
.dimension .field-icon {
  color: #3370ff;
}
.invalid {
  border-color: #bbbfc4;
  color: #8d9199;
}
.placeholder {
  display: block;
  color: #8d9199;
  line-height: 28px;
  padding: 0 16px;
}
.dark {
  .field-drop {
    border-color: #5f5f5f;
    background: #ebebeb0d;
  }
  .field-tag {
    color: #ebebeb;
  }
  .clear-fields {
    color: #a6a6a6;
  }
}
.drop-style {
  :deep(.ed-dropdown-menu__item.menu-item-padding) {
    padding: 5px 16px;
    height: 32px;
    min-width: 218px;
  }
  :deep(.ed-dropdown-menu__item:not(.is-disabled):focus) {
    color: inherit;
    background-color: rgba(31, 35, 41, 0.1);
  }
  &.dark-dimension-quota {
    background-color: #292929;
    border: 1px solid #434343;
    :deep(.ed-dropdown-menu__item) {
      color: rgba(235, 235, 235, 1);
    }
    :deep(.ed-dropdown-menu__item.is-disabled) {
      color: #a6a6a6;
    }
    :deep(.ed-dropdown-menu__item:not(.is-disabled):hover) {
      background-color: #ebebeb1a;
    }
  }
}
</style>
