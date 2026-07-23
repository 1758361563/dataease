<template>
  <!--
    company: single-level cell grid.
    Official version nested 1/2/4 cell patterns (matrixBase=2), which looked
    finer than the actual snap unit and made edges feel misaligned.
    Snap is 1 cell = matrixStyle.width/height; the visual grid must match.
  -->
  <svg class="grid" width="100%" height="100%" xmlns="http://www.w3.org/2000/svg">
    <defs>
      <pattern id="biCellGrid" :width="cellW" :height="cellH" patternUnits="userSpaceOnUse">
        <path :d="cellPath" fill="none" stroke="rgba(31, 35, 41, 0.12)" stroke-width="1" />
      </pattern>
    </defs>
    <rect width="100%" height="100%" fill="url(#biCellGrid)" />
  </svg>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps({
  matrixStyle: {
    type: Object
  },
  themes: {
    type: String,
    default: 'dark'
  }
})

const cellW = computed(() => props.matrixStyle?.width || 0)
const cellH = computed(() => props.matrixStyle?.height || 0)
const cellPath = computed(() => `M ${cellW.value} 0 L 0 0 0 ${cellH.value}`)
</script>

<style lang="less" scoped>
.grid {
  position: absolute;
  top: 0;
  left: 0;
  pointer-events: none;
  z-index: 0;
}
</style>
