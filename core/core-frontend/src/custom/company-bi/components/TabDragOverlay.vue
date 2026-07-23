<template>
  <Teleport to="body">
    <div v-if="visible" class="bi-tab-drag-overlay" :style="overlayStyle" aria-hidden="true">
      <div class="bi-tab-drag-overlay__card">
        <div class="bi-tab-drag-overlay__badge">移入</div>
        <div class="bi-tab-drag-overlay__title">{{ title }}</div>
        <div class="bi-tab-drag-overlay__sub">{{ subtitle }}</div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps({
  visible: { type: Boolean, default: false },
  x: { type: Number, default: 0 },
  y: { type: Number, default: 0 },
  title: { type: String, default: '' },
  subtitle: { type: String, default: '放入当前页签' }
})

const overlayStyle = computed(() => ({
  transform: `translate3d(${props.x + 14}px, ${props.y + 14}px, 0)`
}))
</script>

<style lang="less" scoped>
.bi-tab-drag-overlay {
  position: fixed;
  left: 0;
  top: 0;
  z-index: 10050;
  pointer-events: none;
  will-change: transform;
  transition: none !important;
}

.bi-tab-drag-overlay__card {
  width: 200px;
  max-width: 240px;
  padding: 12px 14px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.92);
  border: 1.5px dashed var(--ed-color-primary, #2563eb);
  box-shadow: 0 8px 24px rgba(31, 35, 41, 0.16);
  color: #1f2329;
}

.bi-tab-drag-overlay__badge {
  display: inline-block;
  margin-bottom: 6px;
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
  color: #fff;
  background: var(--ed-color-primary, #2563eb);
}

.bi-tab-drag-overlay__title {
  font-size: 13px;
  font-weight: 600;
  line-height: 1.3;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bi-tab-drag-overlay__sub {
  margin-top: 4px;
  font-size: 12px;
  color: #646a73;
}
</style>
