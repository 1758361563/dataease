<script setup lang="ts">
defineProps<{ content: string }>()
</script>

<template>
  <el-tooltip
    :content="content"
    placement="top"
    :show-after="200"
    :enterable="false"
    popper-class="company-admin-help-popper"
  >
    <button type="button" class="admin-help" aria-label="查看说明">i</button>
  </el-tooltip>
</template>

<style scoped>
.admin-help {
  display: inline-flex;
  width: 16px;
  height: 16px;
  padding: 0;
  font: 500 11px/1 Georgia, serif;
  color: #8794a8;
  vertical-align: middle;
  cursor: help;
  background: transparent;
  border: 1px solid #a2aec0;
  border-radius: 50%;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
}

.admin-help:focus-visible {
  outline: 2px solid #829bff;
  outline-offset: 3px;
}
</style>
