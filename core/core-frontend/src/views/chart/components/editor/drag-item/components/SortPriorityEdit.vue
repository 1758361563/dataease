<script lang="ts" setup>
import icon_drag_outlined from '@/assets/svg/icon_drag_outlined.svg'
import draggable from 'vuedraggable'
import { computed, reactive, watch, ref } from 'vue'
import { useI18n } from '@/hooks/web/useI18n'
import { activeSortFields } from '../../../js/chartDisplayLimit'
import {
  globalPivotSortReason,
  pivotSortScope
} from '../../../js/panel/charts/table/pivotGlobalSort'
const loading = ref(false)
const state = reactive({ sortList: [], sortScope: 'GROUP' })
const props = defineProps({ chart: { type: Object, required: true } })
const emit = defineEmits(['onPriorityChange'])
const { t } = useI18n()
const directionLabel = { asc: '升序', desc: '降序', custom_sort: '自定义顺序' }
const onUpdate = () =>
  emit(
    'onPriorityChange',
    state.sortList.map(({ id, name, axis, fieldInstanceId }) => ({
      id,
      name,
      axis,
      fieldInstanceId,
      scope: state.sortScope
    }))
  )
const globalReason = computed(() =>
  globalPivotSortReason({ ...props.chart, sortPriority: state.sortList })
)
const changeScope = value => {
  if (value === 'GLOBAL' && globalReason.value) return
  state.sortScope = value
  onUpdate()
}
watch(
  () => props.chart,
  () => {
    state.sortScope = pivotSortScope(props.chart)
    state.sortList = activeSortFields(props.chart).map(field => ({
      id: field.id,
      axis: field.axis,
      fieldInstanceId: field.fieldInstanceId,
      key: field.key,
      sort: field.sort,
      name:
        (field.chartShowName || field.name) +
        (['yAxis', 'yAxisExt'].includes(field.axis)
          ? '（' + t('chart.' + (field.summary || 'sum')) + '）'
          : '')
    }))
    onUpdate()
  },
  { deep: true, immediate: true }
)
</script>

<template>
  <div v-if="chart.type === 'table-pivot'" class="sort-scope">
    <div class="sort-scope-title">排序范围</div>
    <el-radio-group :model-value="state.sortScope" @change="changeScope">
      <el-radio label="GROUP">分组内排序（默认）</el-radio>
      <el-radio label="GLOBAL" :disabled="!!globalReason">跨分组全局排序</el-radio>
    </el-radio-group>
    <p v-if="globalReason" class="sort-scope-tip">{{ globalReason }}</p>
    <p v-else-if="state.sortScope === 'GLOBAL'" class="sort-scope-tip">
      全局排序会逐行显示行维度；行小计不显示，总计仍保留。
    </p>
  </div>
  <el-scrollbar height="100%" max-height="599px">
    <draggable
      v-loading="loading"
      :list="state.sortList"
      animation="300"
      class="drag-list"
      item-key="key"
      @update="onUpdate"
    >
      <template #item="{ element }">
        <span :key="element.key" class="item-dimension" :title="element.name">
          <el-icon class="item-icon">
            <Icon name="icon_drag_outlined"><icon_drag_outlined class="svg-icon" /></Icon>
          </el-icon>
          <span class="item-span"> {{ element.name }} · {{ directionLabel[element.sort] }} </span>
        </span>
      </template>
    </draggable>
  </el-scrollbar>
</template>

<style lang="less" scoped>
.sort-scope {
  padding: 0 2px 12px;
}

.sort-scope-title {
  margin-bottom: 8px;
  color: #1f2329;
  font-size: 14px;
  font-weight: 500;
}

.sort-scope-tip {
  margin: 6px 0 0;
  color: #878d9f;
  font-size: 12px;
  line-height: 18px;
}

.drag-list {
  min-height: 0;
}

.item-dimension {
  padding: 2px;
  margin: 2px;
  border: 1px solid #dee0e3;
  border-radius: 6px;
  text-align: left;
  color: #606266;
  background-color: white;
  display: flex;
  align-items: center;
  cursor: move;
}

.item-icon {
  font-size: 16px;
  margin: 0 4px;
  color: #646a73;
}

.item-span {
  display: inline-block;
  width: 100%;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;

  color: #1f2329;

  font-size: 14px;
  font-style: normal;
  font-weight: 400;
  line-height: 22px;
}

.blackTheme .item-dimension {
  border: solid 1px;
  border-color: var(--TableBorderColor);
  color: var(--TextPrimary);
  background-color: var(--MainBG);
}

.item-dimension + .item-dimension {
  margin-top: 6px;
}

.item-dimension:hover {
  box-shadow: 0px 4px 8px 0px rgba(31, 35, 41, 0.1);
}

.blackTheme .item-dimension:hover {
  color: var(--Main);
  background: var(--ContentBG);
}
</style>
