<script lang="ts" setup>
import AdminHelp from '../AdminHelp.vue'
import { onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus-secondary'
import { listCompanyUsers } from '@/api/company/user'
import { listCompanyGroups } from '@/api/company/group'
import {
  fetchAdminCatalog,
  fetchDirectGrants,
  grantCompanyAcl,
  revokeCompanyAcl
} from '@/api/company/permission'
import type {
  CompanyCatalogNode,
  CompanyDirectAcl,
  CompanyGroup,
  CompanyUser
} from '@/api/company/types'
import { keepStringId } from '@/utils/companyIds'
import {
  dashboardRelationOptions,
  datasetRelationOptions,
  grantPayloadFromUi
} from '@/utils/companyNav'

const loading = ref(false)
const subjectType = ref<'USER' | 'GROUP'>('USER')
const subjectId = ref('')
const users = ref<CompanyUser[]>([])
const groups = ref<CompanyGroup[]>([])
const catalog = ref<{ dashboards: CompanyCatalogNode[]; datasets: CompanyCatalogNode[] }>({
  dashboards: [],
  datasets: []
})
const grantMap = ref<Record<string, string>>({})
const tab = ref('dashboard')
const savingKey = ref('')

const normalizeNodes = (nodes: CompanyCatalogNode[] | undefined): CompanyCatalogNode[] => {
  return (nodes || []).map(node => ({
    ...node,
    id: keepStringId(node.id),
    pid: keepStringId(node.pid),
    children: normalizeNodes(node.children)
  }))
}

const grantKey = (resourceType: string, resourceId: string) => `${resourceType}:${resourceId}`

const loadSubjects = async () => {
  users.value = (await listCompanyUsers()).map(u => ({ ...u, id: keepStringId(u.id) }))
  groups.value = (await listCompanyGroups()).map(g => ({ ...g, id: keepStringId(g.id) }))
}

const loadCatalog = async () => {
  const data = await fetchAdminCatalog()
  catalog.value = {
    dashboards: normalizeNodes(data?.dashboards),
    datasets: normalizeNodes(data?.datasets)
  }
}

const loadGrants = async () => {
  if (!subjectId.value) {
    grantMap.value = {}
    return
  }
  const rows: CompanyDirectAcl[] = await fetchDirectGrants(
    subjectType.value,
    keepStringId(subjectId.value)
  )
  const next: Record<string, string> = {}
  for (const row of rows) {
    const id = keepStringId(row.resourceId)
    next[grantKey(row.resourceType, id)] = row.relation
  }
  grantMap.value = next
}

const currentRelation = (node: CompanyCatalogNode) => {
  const type = node.type === 'DATASET_FOLDER' ? '' : node.type
  if (!type) {
    return 'NONE'
  }
  return grantMap.value[grantKey(type, keepStringId(node.id))] || 'NONE'
}

const onChange = async (node: CompanyCatalogNode, uiRelation: string) => {
  if (!subjectId.value) {
    ElMessage.warning('请先选择授权对象')
    return
  }
  const resourceType =
    node.type === 'DASHBOARD' || node.type === 'DASHBOARD_FOLDER' || node.type === 'DATASET'
      ? node.type
      : null
  if (!resourceType) {
    return
  }
  const key = grantKey(resourceType, keepStringId(node.id))
  const previous = grantMap.value[key] || 'NONE'
  savingKey.value = key
  try {
    const payload = grantPayloadFromUi({
      subjectType: subjectType.value,
      subjectId: subjectId.value,
      resourceType,
      resourceId: node.id,
      uiRelation
    })
    if (payload.kind === 'grant') {
      await grantCompanyAcl(payload.body as any)
      grantMap.value = {
        ...grantMap.value,
        [key]: payload.body.relation === 'READ' ? 'READ' : payload.body.relation
      }
    } else {
      await revokeCompanyAcl(payload.body as any)
      const copy = { ...grantMap.value }
      delete copy[key]
      grantMap.value = copy
    }
  } catch (e: any) {
    grantMap.value = { ...grantMap.value, [key]: previous }
    ElMessage.error(typeof e === 'string' ? e : '保存失败')
  } finally {
    savingKey.value = ''
  }
}

const subjectOptions = () => (subjectType.value === 'USER' ? users.value : groups.value)

watch(subjectType, () => {
  subjectId.value = ''
  grantMap.value = {}
})
watch(subjectId, () => {
  loadGrants()
})

onMounted(async () => {
  loading.value = true
  try {
    await Promise.all([loadSubjects(), loadCatalog()])
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="company-admin-page" v-loading="loading">
    <div class="page-head">
      <span class="admin-title"
        >资源授权
        <AdminHelp
          content="资源关系决定可访问范围；实际编辑能力由账号角色决定。本页编辑的是直接授权，不是继承后的有效权限。"
      /></span>
    </div>

    <div class="admin-panel">
      <div class="toolbar">
        <el-radio-group v-model="subjectType">
          <el-radio-button label="USER">用户</el-radio-button>
          <el-radio-button label="GROUP">用户组</el-radio-button>
        </el-radio-group>
        <el-select v-model="subjectId" filterable placeholder="选择授权对象" class="subject-select">
          <el-option
            v-for="item in subjectOptions()"
            :key="item.id"
            :label="subjectType === 'USER' ? `${(item as CompanyUser).displayName} (${(item as CompanyUser).username})` : (item as CompanyGroup).name"
            :value="item.id"
          />
        </el-select>
      </div>
      <el-tabs v-model="tab" class="admin-tabs">
        <el-tab-pane
          v-for="resource in [
            { name: 'dashboard', label: '仪表板', nodes: catalog.dashboards },
            { name: 'dataset', label: '数据集', nodes: catalog.datasets }
          ]"
          :key="resource.name"
          :label="resource.label"
          :name="resource.name"
        >
          <el-table :data="resource.nodes" row-key="id" default-expand-all class="resource-table">
            <el-table-column prop="name" label="资源名称" min-width="300" show-overflow-tooltip />
            <el-table-column label="类型" width="120"
              ><template #default="{ row }"
                ><el-tag type="info" size="small">{{
                  row.type.endsWith('_FOLDER')
                    ? '文件夹'
                    : row.type === 'DATASET'
                    ? '数据集'
                    : '仪表板'
                }}</el-tag></template
              ></el-table-column
            >
            <el-table-column label="直接授权" width="220"
              ><template #default="{ row }">
                <el-select
                  v-if="['DASHBOARD', 'DASHBOARD_FOLDER', 'DATASET'].includes(row.type)"
                  :model-value="currentRelation(row)"
                  :disabled="!subjectId || savingKey === grantKey(row.type, row.id)"
                  class="grant-select"
                  @change="(val: string) => onChange(row, val)"
                >
                  <el-option
                    v-for="opt in resource.name === 'dashboard'
                      ? dashboardRelationOptions()
                      : datasetRelationOptions()"
                    :key="opt.value"
                    :label="opt.label"
                    :value="opt.value"
                  />
                </el-select> </template
            ></el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<style lang="less" scoped></style>
