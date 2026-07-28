<template>
  <button
    type="button"
    class="bi-card-drag-handle"
    :class="{ 'is-dragging': dragging, 'is-visible': visible }"
    aria-label="移动组件"
    @mousedown.stop.prevent="onDown"
  >
    <span class="bi-drag-dot" />
    <span class="bi-drag-dot" />
    <span class="bi-drag-dot" />
    <span class="bi-drag-dot" />
    <span class="bi-drag-dot" />
    <span class="bi-drag-dot" />
  </button>
</template>

<script setup lang="ts">
defineProps<{
  visible?: boolean
  dragging?: boolean
}>()

const emit = defineEmits<{
  (e: 'dragstart', event: MouseEvent): void
}>()

const onDown = (e: MouseEvent) => {
  emit('dragstart', e)
}
</script>
