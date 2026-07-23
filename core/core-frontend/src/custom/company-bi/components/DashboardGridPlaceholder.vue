<template>
  <div
    v-if="view.active && view.placeholder"
    class="bi-grid-placeholder"
    :style="boxStyle"
    aria-hidden="true"
  />
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { dashboardDragSession } from '@/custom/company-bi/composables/useDashboardDragSession'

const props = defineProps({
  cellWidth: { type: Number, required: true },
  cellHeight: { type: Number, required: true },
  gap: { type: Number, default: 0 }
})

const view = dashboardDragSession.rawView

const boxStyle = computed(() => {
  const ph = view.placeholder
  if (!ph) return {}
  const gap = props.gap || 0
  return {
    left: `${props.cellWidth * (ph.x - 1)}px`,
    top: `${props.cellHeight * (ph.y - 1)}px`,
    width: `${props.cellWidth * ph.sizeX}px`,
    height: `${props.cellHeight * ph.sizeY}px`,
    padding: `${gap}px`
  }
})
</script>

<style lang="less" scoped>
.bi-grid-placeholder {
  position: absolute;
  z-index: 1;
  box-sizing: border-box;
  pointer-events: none;
  transition: none !important;

  &::after {
    content: '';
    display: block;
    width: 100%;
    height: 100%;
    box-sizing: border-box;
    border-radius: 8px;
    border: 1.5px dashed var(--ed-color-primary, #2563eb);
    background: rgba(37, 99, 235, 0.08);
  }
}
</style>
