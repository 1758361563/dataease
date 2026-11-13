<script setup lang="ts">
import { computed, watch } from 'vue'
import dayjs from 'dayjs'
import { createRangeEndpoints, resolveDefaultRange } from './default-date-range'
import type { DefaultRangeConfig } from './default-date-range'

const props = defineProps<{ config: DefaultRangeConfig }>()
watch(
  () => props.config,
  config => {
    if (!config.rangeEndpoints) config.rangeEndpoints = createRangeEndpoints(config)
  },
  { immediate: true }
)
const endpoints = computed(() => props.config.rangeEndpoints)
const sides = [
  { key: 'start', label: '开始日期' },
  { key: 'end', label: '结束日期' }
] as const
const anchors = [
  { value: 'today', label: '今天' },
  { value: 'monthStart', label: '本月月初' },
  { value: 'monthEnd', label: '本月月末' },
  { value: 'yearStart', label: '本年年初' },
  { value: 'yearEnd', label: '本年年末' }
]
const pickerType = computed(
  () =>
    ({
      yearrange: 'year',
      monthrange: 'month',
      daterange: 'date',
      datetimerange: 'datetime'
    }[props.config.timeGranularityMultiple] || 'date')
)
const units = computed(() => {
  const all = [
    { value: 'year', label: '年' },
    { value: 'month', label: '月' },
    { value: 'day', label: '天' }
  ]
  return all
})
const preview = computed(() => {
  const [start, end] = resolveDefaultRange(props.config)
  if (!Number.isFinite(+start) || !Number.isFinite(+end))
    return { error: true, text: '请补全两端的日期设置' }
  if (+start > +end) return { error: true, text: '开始日期不能晚于结束日期' }
  const format =
    pickerType.value === 'datetime'
      ? 'YYYY-MM-DD HH:mm:ss'
      : pickerType.value === 'year'
      ? 'YYYY'
      : pickerType.value === 'month'
      ? 'YYYY-MM'
      : 'YYYY-MM-DD'
  return { error: false, text: `${dayjs(start).format(format)} → ${dayjs(end).format(format)}` }
})
function changeMode(side: 'start' | 'end') {
  const endpoint = endpoints.value[side]
  if (endpoint.mode === 'fixed' && !endpoint.value) {
    endpoint.value = dayjs().format('YYYY-MM-DD') + (side === 'start' ? ' 00:00:00' : ' 23:59:59')
  }
}
</script>

<template>
  <div v-if="endpoints" class="date-range-endpoints">
    <div v-for="side in sides" :key="side.key" class="date-endpoint">
      <div class="endpoint-heading">
        <span>{{ side.label }}</span>
        <el-radio-group
          v-model="endpoints[side.key].mode"
          size="small"
          @change="changeMode(side.key)"
        >
          <el-radio-button label="fixed" value="fixed">固定日期</el-radio-button>
          <el-radio-button label="relative" value="relative">相对日期</el-radio-button>
        </el-radio-group>
      </div>
      <el-date-picker
        v-if="endpoints[side.key].mode === 'fixed'"
        v-model="endpoints[side.key].value"
        :type="pickerType"
        value-format="YYYY-MM-DD HH:mm:ss"
        :clearable="false"
        placeholder="选择日期"
      />
      <template v-else>
        <el-select v-model="endpoints[side.key].anchor" aria-label="相对日期基准">
          <el-option
            v-for="anchor in anchors"
            :key="anchor.value"
            :label="anchor.label"
            :value="anchor.value"
          />
        </el-select>
        <div class="endpoint-offset">
          <el-select v-model="endpoints[side.key].direction" aria-label="偏移方向">
            <el-option label="往前" value="before" /><el-option label="往后" value="after" />
          </el-select>
          <el-input-number
            v-model="endpoints[side.key].amount"
            :min="0"
            :max="10000"
            :precision="0"
            controls-position="right"
            aria-label="偏移数量"
          />
          <el-select v-model="endpoints[side.key].unit" aria-label="偏移单位">
            <el-option
              v-for="unit in units"
              :key="unit.value"
              :label="unit.label"
              :value="unit.value"
            />
          </el-select>
        </div>
        <el-time-picker
          v-if="pickerType === 'datetime'"
          v-model="endpoints[side.key].time"
          value-format="HH:mm:ss"
          :clearable="false"
          placeholder="时分秒"
        />
      </template>
    </div>
    <div class="range-preview" :class="{ invalid: preview.error }" aria-live="polite">
      {{ preview.text }}
    </div>
  </div>
</template>

<style scoped lang="less">
.date-range-endpoints {
  display: grid;
  width: 100%;
  gap: 12px;
}
.date-endpoint {
  display: grid;
  min-width: 0;
  gap: 8px;
}
.endpoint-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.endpoint-offset {
  display: grid;
  grid-template-columns: 1fr 1.2fr 1fr;
  gap: 6px;
}
.date-range-endpoints :deep(.ed-input-number),
.date-range-endpoints :deep(.ed-select),
.date-range-endpoints :deep(.ed-date-editor) {
  width: 100%;
  min-width: 0;
}
.range-preview {
  font-size: 12px;
  color: #667085;
  overflow-wrap: anywhere;
}
.invalid {
  color: #d84c4c;
}
</style>
