<template>
  <div class="shadow-main" :style="shadowStyle">
    <div class="shadow-background"></div>
  </div>
</template>

<script setup lang="ts">
import { computed, toRefs } from 'vue'
const props = defineProps({
  baseWidth: {
    required: true,
    type: Number
  },
  baseHeight: {
    required: true,
    type: Number
  },
  curGap: {
    required: true,
    type: Number
  },
  element: {
    required: true,
    type: Object,
    default() {
      return {
        component: null,
        propValue: null,
        request: null,
        linkage: null,
        type: null,
        events: null,
        style: null,
        id: null
      }
    }
  }
})

const { element, baseWidth, baseHeight, curGap } = toRefs(props)

const shadowStyle = computed(() => {
  const { x, y, sizeX, sizeY } = element.value
  // Snap placeholder: must update instantly (no CSS transition).
  // Use transform for compositor-friendly moves (Metabase / RGL pattern).
  const left = (x - 1) * baseWidth.value
  const top = (y - 1) * baseHeight.value
  return {
    padding: curGap.value + 'px',
    width: sizeX * baseWidth.value + 'px',
    height: sizeY * baseHeight.value + 'px',
    transform: `translate3d(${left}px, ${top}px, 0)`
  }
})
</script>

<style lang="less" scoped>
.shadow-main {
  position: absolute;
  left: 0;
  top: 0;
  /* company: NEVER transition during drag — transition = intentional mouse lag */
  transition: none !important;
  pointer-events: none;
  z-index: 1;
  box-sizing: border-box;
  will-change: transform, width, height;
}
.shadow-background {
  width: 100%;
  height: 100%;
  box-sizing: border-box;
  border-radius: 8px;
  background: rgba(37, 99, 235, 0.1);
  border: 1.5px dashed rgba(37, 99, 235, 0.55);
}
</style>
