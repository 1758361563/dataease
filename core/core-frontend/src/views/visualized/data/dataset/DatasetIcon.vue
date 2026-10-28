<script setup lang="ts">
withDefaults(defineProps<{ name?: string }>(), { name: 'filter' })
const paths = {
  plus: 'M12 5v14M5 12h14',
  expand: 'M9 5l7 7-7 7',
  collapse: 'M15 5l-7 7 7 7',
  filter: 'M4 5h16M7 12h10M10 19h4',
  search: 'M21 21l-4.4-4.4M19 11a8 8 0 1 1-16 0 8 8 0 0 1 16 0',
  close: 'M6 6l12 12M18 6L6 18',
  columns: 'M8 4v16M16 4v16M4 4h16v16H4z',
  panel: 'M8 3v18M3 3h18v18H3z',
  down: 'M6 9l6 6 6-6',
  download: 'M12 3v12m-5-5 5 5 5-5M4 16v5h16v-5',
  refresh: 'M20 7v5h-5M4 17v-5h5M6 8a7 7 0 0 1 11.5-3L20 8M4 16l2.5 3A7 7 0 0 0 18 16',
  eye: 'M2 12s4-7 10-7 10 7 10 7-4 7-10 7-10-7-10-7m13 0a3 3 0 1 1-6 0 3 3 0 0 1 6 0',
  check: 'M5 12l4 4L19 6'
}
</script>
<template>
  <svg
    class="dataset-icon"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.65"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <path :d="paths[name] || paths.filter" />
  </svg>
</template>
<style scoped>
.dataset-icon {
  width: 16px;
  height: 16px;
  flex-shrink: 0;
  vertical-align: middle;
}
</style>
