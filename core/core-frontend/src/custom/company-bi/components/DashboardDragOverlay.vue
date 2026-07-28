<template>
  <Teleport to="body">
    <div
      v-if="view.active && view.overlay && tabDragIntent !== 'insert'"
      class="bi-dash-drag-overlay"
      :style="overlayStyle"
      aria-hidden="true"
    >
      <div class="bi-dash-drag-overlay__card">
        <div class="bi-dash-drag-overlay__title">{{ view.label || '组件' }}</div>
        <div class="bi-dash-drag-overlay__hint">
          {{ view.mode === 'resize' ? '调整大小' : '拖动中' }}
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { storeToRefs } from 'pinia'
import { dashboardDragSession } from '@/custom/company-bi/composables/useDashboardDragSession'
import { dvMainStoreWithOut } from '@/store/modules/data-visualization/dvMain'

const view = dashboardDragSession.rawView
// company: confirmed tab-insert switches the follow visual to the compact
// TabDragOverlay chip; the full-size card here would suggest canvas placement
const { tabDragIntent } = storeToRefs(dvMainStoreWithOut())

const overlayStyle = computed(() => {
  const o = view.overlay
  if (!o) return {}
  return {
    width: `${o.width}px`,
    height: `${o.height}px`,
    transform: `translate3d(${o.x}px, ${o.y}px, 0)`
  }
})
</script>

<style lang="less" scoped>
.bi-dash-drag-overlay {
  position: fixed;
  left: 0;
  top: 0;
  z-index: 10040;
  pointer-events: none;
  will-change: transform;
  transition: none !important;
  box-sizing: border-box;
}

.bi-dash-drag-overlay__card {
  width: 100%;
  height: 100%;
  box-sizing: border-box;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.88);
  border: 1.5px solid var(--ed-color-primary, #2563eb);
  box-shadow: 0 8px 24px rgba(31, 35, 41, 0.16);
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 12px 14px;
  color: #1f2329;
}

.bi-dash-drag-overlay__title {
  font-size: 13px;
  font-weight: 600;
  line-height: 1.3;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bi-dash-drag-overlay__hint {
  margin-top: 4px;
  font-size: 12px;
  color: #646a73;
}
</style>
