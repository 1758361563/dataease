<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { planNameRestore, restoreCandidates, type NamedField } from './fieldNaming'

const props = defineProps<{ modelValue: boolean; fields: NamedField[] }>()
const emit = defineEmits(['update:modelValue', 'apply'])
const selected = ref<string[]>([])
const error = ref('')
const candidates = computed(() => restoreCandidates(props.fields))
watch(
  () => props.modelValue,
  () => {
    selected.value = []
    error.value = ''
  }
)
watch(
  selected,
  () => {
    error.value = ''
  },
  { deep: true }
)
const apply = () => {
  try {
    const changes = planNameRestore(props.fields, selected.value)
    if (!changes.length) return
    emit('apply', changes)
    emit('update:modelValue', false)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '无法恢复字段名称'
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="恢复原字段名"
    width="min(860px, 94vw)"
    class="dataset-modern-field-dialog"
    modal-class="dataset-centered-overlay"
    append-to-body
    @update:model-value="value => emit('update:modelValue', value)"
  >
    <div class="restore-tools">
      <el-button
        text
        @click="selected = candidates.filter(field => field.fromComment).map(field => field.id)"
        >选择名称与备注相同的字段</el-button
      >
      <el-button text @click="selected = []">清空选择</el-button>
      <span>{{ selected.length }} 项待恢复</span>
    </div>
    <p v-if="error" role="alert" class="restore-error">{{ error }}</p>
    <div class="restore-list">
      <table v-if="candidates.length">
        <thead>
          <tr>
            <th aria-label="选择"></th>
            <th>当前显示名称</th>
            <th>恢复为</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="field in candidates" :key="field.id">
            <td>
              <el-checkbox v-model="selected" :label="field.id" :aria-label="'恢复 ' + field.before"
                ><span></span
              ></el-checkbox>
            </td>
            <td :title="field.description ? '备注：' + field.description : field.before">
              {{ field.before }}
            </td>
            <td>{{ field.after }}</td>
          </tr>
        </tbody>
      </table>
      <p v-else>没有需要恢复名称的物理字段</p>
    </div>
    <template #footer>
      <span class="restore-note">应用到当前草稿，保存数据集后生效</span>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :disabled="!selected.length" @click="apply">应用所选修改</el-button>
    </template>
  </el-dialog>
</template>

<style scoped lang="less">
.restore-tools {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  span {
    margin-left: auto;
    color: #64748b;
    font-size: 13px;
  }
}
.restore-list {
  max-height: 52vh;
  overflow: auto;
  table {
    width: 100%;
    border-collapse: collapse;
    table-layout: fixed;
    font-size: 14px;
  }
  th,
  td {
    padding: 10px 12px;
    text-align: left;
    border-bottom: 1px solid #e8edf3;
    overflow-wrap: anywhere;
  }
  th {
    position: sticky;
    top: 0;
    z-index: 1;
    background: #f8fafc;
    color: #64748b;
    font-weight: 500;
  }
  th:first-child {
    width: 44px;
  }
}
.restore-error {
  color: #dc2626;
}
.restore-note {
  float: left;
  color: #64748b;
  font-size: 13px;
  line-height: 32px;
}
</style>
