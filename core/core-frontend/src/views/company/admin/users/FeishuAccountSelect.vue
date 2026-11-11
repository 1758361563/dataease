<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import request from '@/config/axios'
import type { FeishuEmployeeMatch } from '@/api/company/feishu'

const props = defineProps<{ modelValue: string; initialName?: string; biUserId?: string }>()
const emit = defineEmits<{ (event: 'update:modelValue', value: string): void }>()
const options = ref<Array<FeishuEmployeeMatch & { boundUserId?: string; boundUsername?: string }>>(
  []
)
const loading = ref(false)
const error = ref('')
let sequence = 0
let timer: ReturnType<typeof setTimeout>
watch(
  () => [props.modelValue, props.initialName],
  () => {
    if (props.modelValue && !options.value.some(row => row.userId === props.modelValue)) {
      options.value.unshift({
        userId: props.modelValue,
        name: props.initialName || props.modelValue,
        departments: [],
        userIdTail: props.modelValue.slice(-4)
      })
    }
  },
  { immediate: true }
)
function search(query: string) {
  clearTimeout(timer)
  const id = ++sequence
  error.value = ''
  if (!query.trim()) {
    loading.value = false
    return
  }
  loading.value = true
  timer = setTimeout(async () => {
    try {
      const result = await request.get({
        url: '/company/bi/admin/users/feishu/search',
        params: { query: query.trim() }
      })
      if (id === sequence) options.value = result.data?.matches || []
    } catch {
      if (id === sequence) error.value = '联系人读取失败，请重新搜索'
    } finally {
      if (id === sequence) loading.value = false
    }
  }, 300)
}
onBeforeUnmount(() => {
  clearTimeout(timer)
  sequence++
})
</script>

<template>
  <div class="feishu-account-select">
    <el-select
      :model-value="modelValue"
      clearable
      filterable
      remote
      :remote-method="search"
      :loading="loading"
      placeholder="搜索飞书姓名，可选"
      @update:model-value="emit('update:modelValue', $event || '')"
    >
      <el-option
        v-for="item in options"
        :key="item.userId"
        :value="item.userId"
        :label="`${item.name}（${item.userId}）`"
        :disabled="!!item.boundUserId && item.boundUserId !== biUserId"
      >
        <span>{{ item.name }}（{{ item.userId }}）</span>
        <span v-if="item.boundUserId" class="identity-detail">已绑定 {{ item.boundUsername }}</span>
        <span class="identity-detail">{{ item.departments.join(' / ') }}</span>
      </el-option>
    </el-select>
    <span v-if="error" class="error">{{ error }}</span>
  </div>
</template>

<style scoped>
.feishu-account-select,
.el-select {
  width: 100%;
}

.identity-detail {
  margin-left: 12px;
  font-size: 12px;
  color: #8a94a6;
}

.error {
  font-size: 12px;
  color: #d84c4c;
}
</style>
