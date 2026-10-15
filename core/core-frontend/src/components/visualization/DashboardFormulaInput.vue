<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { DashboardCalculationConfig } from '@/models/dashboardCalculation'
import { highlightFormulaReferences } from '@/utils/dashboardFormulaReferences'

const props = defineProps<{
  modelValue: string
  config: DashboardCalculationConfig
  datasetId: string
  fields?: any[]
  placeholder?: string
}>()
const emit = defineEmits<{
  (event: 'update:modelValue', value: string): void
  (event: 'focus'): void
}>()
const draft = ref(props.modelValue || '')
const input = ref<HTMLTextAreaElement>()
const highlight = ref<HTMLElement>()
const contentWidth = ref(0)
const segments = computed(() =>
  highlightFormulaReferences(draft.value, props.config, props.datasetId, props.fields)
)
let composing = false
let observer: ResizeObserver | undefined
const syncScroll = () => {
  if (!input.value || !highlight.value) return
  contentWidth.value = input.value.clientWidth
  highlight.value.scrollTop = input.value.scrollTop
  highlight.value.scrollLeft = input.value.scrollLeft
}
const changed = (event: Event) => {
  draft.value = (event.target as HTMLTextAreaElement).value
  if (!composing) emit('update:modelValue', draft.value)
  void nextTick(syncScroll)
}
const compositionEnd = (event: CompositionEvent) => {
  composing = false
  changed(event)
}
watch(
  () => props.modelValue,
  value => {
    draft.value = value || ''
    void nextTick(syncScroll)
  }
)
onMounted(() => {
  observer = new ResizeObserver(syncScroll)
  if (input.value) observer.observe(input.value)
  syncScroll()
})
onBeforeUnmount(() => observer?.disconnect())
</script>

<template>
  <div class="formula-input">
    <pre
      ref="highlight"
      class="formula-highlight"
      aria-hidden="true"
      :style="{ width: contentWidth ? `${contentWidth}px` : 'calc(100% - 2px)' }"
    ><template v-for="(segment, index) in segments" :key="index"><span :class="segment.kind && `reference-${segment.kind}`">{{ segment.text }}</span></template><span>&#8203;</span></pre>
    <textarea
      ref="input"
      :value="draft"
      :placeholder="placeholder"
      aria-label="计算公式"
      :spellcheck="false"
      rows="4"
      wrap="soft"
      @input="changed"
      @scroll="syncScroll"
      @focus="emit('focus')"
      @compositionstart="composing = true"
      @compositionend="compositionEnd"
    />
  </div>
</template>

<style scoped lang="less">
.formula-input {
  position: relative;
  width: 100%;
  font-size: 14px;
  line-height: 1.6;
  background: var(--ed-fill-color-blank, var(--el-bg-color, #fff));
  border-radius: 4px;
}
.formula-highlight,
textarea {
  box-sizing: border-box;
  margin: 0;
  padding: 8px 12px;
  font: inherit;
  letter-spacing: inherit;
  white-space: pre-wrap;
  overflow-wrap: break-word;
  word-break: normal;
  tab-size: 4;
}
.formula-highlight {
  position: absolute;
  top: 1px;
  left: 1px;
  height: calc(100% - 2px);
  overflow: hidden;
  pointer-events: none;
  color: var(--ed-text-color-primary, var(--el-text-color-primary, #1f2329));
}
textarea {
  position: relative;
  display: block;
  width: 100%;
  min-height: 108px;
  resize: vertical;
  border: 1px solid var(--ed-border-color, var(--el-border-color, #dcdfe6));
  border-radius: inherit;
  outline: none;
  background: transparent;
  color: transparent;
  -webkit-text-fill-color: transparent;
  caret-color: var(--ed-text-color-primary, var(--el-text-color-primary, #1f2329));
  &:hover {
    border-color: var(--ed-border-color-hover, #a8abb2);
  }
  &:focus {
    border-color: var(--ed-color-primary, var(--el-color-primary, #3370ff));
  }
  &::placeholder {
    color: var(--ed-text-color-placeholder, #8f959e);
    -webkit-text-fill-color: var(--ed-text-color-placeholder, #8f959e);
  }
  &::selection {
    background: rgba(64, 158, 255, 0.25);
  }
}
.reference-dataset {
  color: #2563eb;
}
.reference-shared {
  color: #7c3aed;
}
.reference-parameter {
  color: #087f8c;
}
</style>
