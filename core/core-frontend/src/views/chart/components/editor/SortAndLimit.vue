<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from '@/hooks/web/useI18n'
import { activeSortFields, displayLimitReason, displayCountPatch } from '../js/chartDisplayLimit'
import icon_info_outlined from '@/assets/svg/icon_info_outlined.svg'
const props = defineProps<{ chart: ChartObj; themes: string }>()
const emit = defineEmits(['change'])
const { t } = useI18n()
const reason = computed(() => displayLimitReason(props.chart))
const count = computed(() =>
  props.chart.resultMode === 'custom' ? props.chart.resultCount : undefined
)
const customRequested = ref(false)
watch(
  () => props.chart.id,
  () => {
    customRequested.value = false
  }
)
const preset = computed(() =>
  count.value === undefined
    ? 'all'
    : customRequested.value
    ? 'custom'
    : [20, 50].includes(count.value)
    ? String(count.value)
    : 'custom'
)
const description = computed(() => {
  const fields = activeSortFields(props.chart)
  const amount = count.value === undefined ? '全部分类' : '前 ' + count.value + ' 项'
  if (!fields.length) return '按默认顺序显示' + amount + '，未设置排名依据。'
  const rules = fields.map(field => {
    const summary = ['yAxis', 'yAxisExt'].includes(field.axis)
      ? '（' + t('chart.' + (field.summary || 'sum')) + '）'
      : ''
    return (
      (field.chartShowName || field.name) +
      summary +
      ' · ' +
      { asc: '升序', desc: '降序', custom_sort: '自定义顺序' }[field.sort]
    )
  })
  return '按' + rules.join('，相同时再按') + '，显示' + amount + '。'
})
function changeCount(value: string) {
  customRequested.value = value === 'custom'
  emit(
    'change',
    displayCountPatch(
      value === 'all' ? undefined : value === 'custom' ? count.value || 20 : Number(value)
    )
  )
}
</script>

<template>
  <section class="sort-limit" :class="{ dark: themes === 'dark' }">
    <div class="heading">
      <span>显示数量</span>
      <el-tooltip :effect="themes" placement="top" :enterable="false" :show-after="250">
        <template #content>
          <div class="limit-help">
            <template v-if="reason">
              <p>{{ reason }}</p>
              <p v-if="count !== undefined">
                当前保留旧的 {{ count }} 行限制，不代表前 {{ count }} 个完整分类。
                可点击“取消行数限制”查询全部结果。
              </p>
            </template>
            <template v-else>
              <p>{{ description }}</p>
              <p v-if="chart.type.startsWith('pie') && count !== undefined">
                占比按保留的分类重新计算，不自动合并“其他”；负值不绘制扇区，实际数量可能少于上限。
              </p>
            </template>
          </div>
        </template>
        <button type="button" class="help-icon" aria-label="显示数量说明">
          <el-icon :size="14"><icon_info_outlined class="svg-icon" /></el-icon>
        </button>
      </el-tooltip>
    </div>
    <div class="control-row">
      <template v-if="reason">
        <el-select
          aria-label="显示数量"
          class="rank-control"
          :model-value="count === undefined ? 'all' : 'legacy'"
          :effect="themes"
          size="small"
          disabled
        >
          <el-option label="全部" value="all" />
          <el-option :label="`${count} 行`" value="legacy" />
        </el-select>
        <el-button
          v-if="count !== undefined"
          link
          type="primary"
          size="small"
          :effect="themes"
          @click="emit('change', { resultMode: 'all' })"
          >取消行数限制</el-button
        >
      </template>
      <template v-else>
        <el-select
          aria-label="显示数量"
          class="rank-control"
          :model-value="preset"
          :effect="themes"
          size="small"
          @change="changeCount"
        >
          <el-option label="全部" value="all" /><el-option label="前 20 项" value="20" /><el-option
            label="前 50 项"
            value="50"
          /><el-option label="自定义" value="custom" />
        </el-select>
      </template>
    </div>
    <div v-if="!reason && preset === 'custom'" class="control-row">
      <span>分类数量</span>
      <el-input-number
        class="rank-control"
        aria-label="分类数量"
        :model-value="count"
        :min="1"
        :max="1000000"
        :precision="0"
        :step-strictly="true"
        :value-on-clear="20"
        :controls="false"
        :effect="themes"
        size="small"
        @change="value => emit('change', displayCountPatch(value))"
      />
    </div>
  </section>
</template>

<style scoped lang="less">
.sort-limit {
  width: 100%;
  font-size: 12px;
  .heading {
    display: flex;
    align-items: center;
    gap: 4px;
    line-height: 20px;
  }
  .help-icon {
    display: inline-flex;
    align-items: center;
    padding: 0;
    border: 0;
    background: transparent;
    color: #646a73;
    cursor: help;
  }
  .control-row {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-top: 8px;
    > span {
      flex: 0 0 56px;
    }
    .rank-control {
      flex: 1;
      min-width: 0;
      width: 100%;
    }
  }
  &.dark .help-icon {
    color: #a6a6a6;
  }
}
.limit-help {
  max-width: 280px;
  line-height: 1.6;
  p {
    margin: 0;
    + p {
      margin-top: 6px;
    }
  }
}
</style>
