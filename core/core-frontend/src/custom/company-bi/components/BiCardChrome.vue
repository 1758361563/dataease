<template>
  <div
    class="bi-card-chrome"
    :class="{
      'is-configure': isConfigure,
      'is-active': active,
      'is-hovered': hovered
    }"
  >
    <!-- top-center: 6-dot drag only — no title bar, no divider -->
    <div v-show="!isConfigure" class="bi-card-chrome__drag">
      <BiCardDragHandle
        :visible="showHandles"
        :dragging="dragging"
        @dragstart="e => $emit('dragstart', e)"
      />
    </div>

    <!-- top-right: ⋯ or ✓完成 -->
    <div class="bi-card-chrome__actions" data-no-drag>
      <button
        v-if="isConfigure"
        type="button"
        class="bi-card-finish"
        data-no-drag
        @mousedown.stop
        @click.stop="$emit('finish')"
      >
        <span class="bi-card-finish__check" aria-hidden="true">✓</span>
        <span>完成</span>
      </button>
      <div v-else class="bi-card-chrome__more-slot" data-no-drag>
        <slot name="more" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import BiCardDragHandle from './BiCardDragHandle.vue'

defineProps<{
  isConfigure?: boolean
  active?: boolean
  hovered?: boolean
  showHandles?: boolean
  dragging?: boolean
}>()

defineEmits<{
  (e: 'dragstart', event: MouseEvent): void
  (e: 'finish'): void
}>()
</script>
