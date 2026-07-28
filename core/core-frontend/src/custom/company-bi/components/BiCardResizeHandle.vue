<template>
  <button
    type="button"
    class="bi-card-resize-handle"
    :class="{ 'is-resizing': resizing }"
    aria-label="调整组件大小"
    @mousedown.stop.prevent="onDown"
  >
    <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true">
      <path d="M11.5 14.5L14.5 11.5" />
      <path d="M7.5 14.5L14.5 7.5" />
      <path d="M3.5 14.5L14.5 3.5" />
    </svg>
  </button>
</template>

<script setup lang="ts">
defineProps<{
  resizing?: boolean
}>()

const emit = defineEmits<{
  (e: 'resizestart', event: MouseEvent): void
}>()

const onDown = (e: MouseEvent) => {
  emit('resizestart', e)
}
</script>
