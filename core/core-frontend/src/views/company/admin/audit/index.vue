<script lang="ts" setup>
import { onMounted, ref } from 'vue'
import GridTable from '@/components/grid-table/src/GridTable.vue'
import { queryCompanyAudit } from '@/api/company/audit'
import { listCompanyUsers } from '@/api/company/user'
import type { CompanyAuditRecord, CompanyUser } from '@/api/company/types'
import { keepStringId } from '@/utils/companyIds'

const loading = ref(false)
const records = ref<CompanyAuditRecord[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const users = ref<CompanyUser[]>([])
const filters = ref({
  operatorUserId: '',
  actionType: '',
  targetType: '',
  fromTime: undefined as number | undefined,
  toTime: undefined as number | undefined
})
const range = ref<Date[]>([])
const expanded = ref('')

const pretty = (raw?: string) => {
  if (!raw) return '-'
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}

const formatTime = (ms?: number) => (ms ? new Date(ms).toLocaleString('zh-CN') : '-')

const operatorLabel = (userId?: string) => {
  const id = keepStringId(userId)
  const user = users.value.find(u => keepStringId(u.id) === id)
  return user ? `${user.displayName} (${user.username})` : id || '-'
}

const load = async () => {
  loading.value = true
  try {
    if (range.value?.length === 2) {
      filters.value.fromTime = range.value[0].getTime()
      filters.value.toTime = range.value[1].getTime()
    } else {
      filters.value.fromTime = undefined
      filters.value.toTime = undefined
    }
    const data = await queryCompanyAudit({
      operatorUserId: filters.value.operatorUserId || undefined,
      actionType: filters.value.actionType || undefined,
      targetType: filters.value.targetType || undefined,
      fromTime: filters.value.fromTime,
      toTime: filters.value.toTime,
      page: page.value,
      pageSize: pageSize.value
    })
    total.value = data?.total || 0
    records.value = (data?.records || []).map(r => ({
      ...r,
      id: keepStringId(r.id),
      operatorUserId: keepStringId(r.operatorUserId),
      targetId: r.targetId ? keepStringId(r.targetId) : undefined
    }))
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  users.value = (await listCompanyUsers()).map(u => ({ ...u, id: keepStringId(u.id) }))
  await load()
})
</script>

<template>
  <div class="company-admin-page" v-loading="loading">
    <div class="page-head">审计日志</div>
    <div class="toolbar">
      <el-select
        v-model="filters.operatorUserId"
        clearable
        filterable
        placeholder="操作者"
        style="width: 200px"
      >
        <el-option
          v-for="u in users"
          :key="u.id"
          :label="`${u.displayName} (${u.username})`"
          :value="u.id"
        />
      </el-select>
      <el-input v-model="filters.actionType" clearable placeholder="Action" style="width: 180px" />
      <el-input
        v-model="filters.targetType"
        clearable
        placeholder="Target Type"
        style="width: 160px"
      />
      <el-date-picker
        v-model="range"
        type="datetimerange"
        start-placeholder="开始"
        end-placeholder="结束"
      />
      <el-button type="primary" @click="load">查询</el-button>
    </div>
    <GridTable
      :show-pagination="true"
      :table-data="records"
      :pagination="{ currentPage: page, pageSize, total }"
      @current-change="(p: number) => { page = p; load() }"
      @size-change="(s: number) => { pageSize = s; page = 1; load() }"
    >
      <el-table-column label="时间" min-width="170">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作者" min-width="160">
        <template #default="{ row }">{{ operatorLabel(row.operatorUserId) }}</template>
      </el-table-column>
      <el-table-column prop="actionType" label="Action" min-width="140" />
      <el-table-column prop="targetType" label="Target Type" width="140" />
      <el-table-column prop="targetId" label="Target ID" min-width="180" />
      <el-table-column label="详情" width="90">
        <template #default="{ row }">
          <el-button text @click="expanded = expanded === row.id ? '' : row.id">展开</el-button>
        </template>
      </el-table-column>
    </GridTable>
    <el-card v-if="expanded" shadow="never" class="json-card">
      <div class="json-title">before</div>
      <pre>{{ pretty(records.find(r => r.id === expanded)?.beforeJson) }}</pre>
      <div class="json-title">after</div>
      <pre>{{ pretty(records.find(r => r.id === expanded)?.afterJson) }}</pre>
    </el-card>
  </div>
</template>

<style lang="less" scoped>
.company-admin-page {
  padding: 16px 24px;
}
.page-head {
  font-size: 20px;
  font-weight: 500;
  margin-bottom: 16px;
}
.toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}
.json-card {
  margin-top: 12px;
  .json-title {
    color: #646a73;
    font-size: 12px;
    margin-bottom: 4px;
  }
  pre {
    white-space: pre-wrap;
    word-break: break-all;
    font-size: 12px;
  }
}
</style>
