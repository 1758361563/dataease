<script lang="ts" setup>
import { PropType, reactive, watch } from 'vue'
import { useI18n } from '@/hooks/web/useI18n'
import { DEFAULT_INDICATOR_COMBO_LAYOUT } from './defaults'
import { cloneDeep, defaultsDeep } from 'lodash-es'

const { t } = useI18n()

const props = defineProps({
  chart: {
    type: Object,
    required: true
  },
  themes: {
    type: String as PropType<EditorTheme>,
    default: 'dark'
  }
})

const emit = defineEmits(['onIndicatorComboLayoutChange'])

const state = reactive({
  form: cloneDeep(DEFAULT_INDICATOR_COMBO_LAYOUT) as typeof DEFAULT_INDICATOR_COMBO_LAYOUT
})

const init = () => {
  const raw = props.chart?.customAttr?.indicatorCombo
  state.form = defaultsDeep(cloneDeep(raw || {}), cloneDeep(DEFAULT_INDICATOR_COMBO_LAYOUT))
}

watch(
  () => props.chart,
  () => init(),
  { deep: false, immediate: true }
)

const change = (prop: string) => {
  emit('onIndicatorComboLayoutChange', cloneDeep(state.form), prop)
}

const layoutOptions = [
  { value: 'columns', label: t('chart.indicator_combo_layout_columns') },
  { value: 'row', label: t('chart.indicator_combo_layout_row') },
  { value: 'list', label: t('chart.indicator_combo_layout_list') }
]

const alignOptions = [
  { value: 'center', label: t('chart.indicator_combo_align_center') },
  { value: 'left', label: t('chart.indicator_combo_align_left') }
]
</script>

<template>
  <el-form ref="form" :model="state.form" size="small" label-position="top">
    <el-form-item
      class="form-item"
      :class="'form-item-' + themes"
      :label="t('chart.indicator_combo_layout')"
    >
      <el-radio-group
        v-model="state.form.layout"
        :effect="themes"
        size="small"
        @change="change('layout')"
      >
        <el-radio v-for="opt in layoutOptions" :key="opt.value" :effect="themes" :label="opt.value">
          {{ opt.label }}
        </el-radio>
      </el-radio-group>
    </el-form-item>

    <el-form-item
      class="form-item"
      :class="'form-item-' + themes"
      :label="t('chart.indicator_combo_align')"
    >
      <el-radio-group
        v-model="state.form.align"
        :effect="themes"
        size="small"
        @change="change('align')"
      >
        <el-radio v-for="opt in alignOptions" :key="opt.value" :effect="themes" :label="opt.value">
          {{ opt.label }}
        </el-radio>
      </el-radio-group>
    </el-form-item>

    <el-form-item
      class="form-item"
      :class="'form-item-' + themes"
      :label="t('chart.indicator_combo_divider')"
    >
      <el-switch
        v-model="state.form.showDivider"
        :effect="themes"
        size="small"
        @change="change('showDivider')"
      />
    </el-form-item>
  </el-form>
</template>

<style scoped lang="less">
.el-radio-group {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
}
</style>
