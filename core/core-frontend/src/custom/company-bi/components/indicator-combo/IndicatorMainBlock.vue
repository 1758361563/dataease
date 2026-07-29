<script setup lang="ts">
import { CSSProperties, PropType, computed } from 'vue'

const props = defineProps({
  label: {
    type: String,
    default: ''
  },
  value: {
    type: [String, Number],
    default: '-'
  },
  suffix: {
    type: String,
    default: ''
  },
  showLabel: {
    type: Boolean,
    default: true
  },
  showSuffix: {
    type: Boolean,
    default: false
  },
  labelStyle: {
    type: Object as PropType<CSSProperties>,
    default: () => ({})
  },
  valueStyle: {
    type: Object as PropType<CSSProperties>,
    default: () => ({})
  },
  suffixStyle: {
    type: Object as PropType<CSSProperties>,
    default: () => ({})
  },
  /** fill: 占满可用高度并垂直居中（单指标卡） */
  fill: {
    type: Boolean,
    default: false
  }
})

/**
 * 字号/字重由 indicator-card.less 统一控制层级；
 * 这里只透传颜色、字体族等，避免编辑器默认 44px/bold 把观远层级冲掉。
 */
const omitTypeScale = (style: CSSProperties = {}) => {
  const next = { ...style }
  delete next.fontSize
  delete next['font-size']
  delete next.fontWeight
  delete next['font-weight']
  delete next.letterSpacing
  delete next['letter-spacing']
  return next
}

const labelStyleClean = computed(() => omitTypeScale(props.labelStyle))
const valueStyleClean = computed(() => omitTypeScale(props.valueStyle))
const suffixStyleClean = computed(() => omitTypeScale(props.suffixStyle))
</script>

<template>
  <div class="bi-indicator-main" :class="{ 'is-fill': fill }">
    <div v-if="showLabel && label" class="bi-indicator-main__label" :style="labelStyleClean">
      {{ label }}
    </div>
    <div class="bi-indicator-main__value-row">
      <span class="bi-indicator-main__value" :style="valueStyleClean">{{ value }}</span>
      <span
        v-if="showSuffix && suffix"
        class="bi-indicator-main__suffix"
        :style="suffixStyleClean"
        >{{ suffix }}</span
      >
    </div>
  </div>
</template>
