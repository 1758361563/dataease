<script setup lang="ts">
import DatasetIcon from './DatasetIcon.vue'
defineProps<{ disabled?: boolean }>()
const emit = defineEmits(['create'])
</script>
<template>
  <el-dropdown :disabled="disabled" @command="command => emit('create', command)">
    <el-button text :disabled="disabled" class="dataset-create-field"
      ><DatasetIcon name="plus" />新建字段<DatasetIcon name="down"
    /></el-button>
    <template #dropdown
      ><el-dropdown-menu
        ><el-dropdown-item command="calc">计算字段</el-dropdown-item
        ><el-dropdown-item command="group">分组字段</el-dropdown-item></el-dropdown-menu
      ></template
    >
  </el-dropdown>
</template>
<style scoped lang="less">
.dataset-create-field {
  height: 32px;
  padding: 7px 10px;
  border-radius: 5px;
  color: #536b8b;
  font-size: 13px;
  :deep(> span) {
    display: flex;
    align-items: center;
    gap: 6px;
  }
  .dataset-icon {
    width: 15px;
    height: 15px;
  }
}
</style>
