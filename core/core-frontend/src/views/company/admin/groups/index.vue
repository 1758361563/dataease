<script lang="ts" setup>
import { onMounted, ref, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus-secondary'
import GridTable from '@/components/grid-table/src/GridTable.vue'
import {
  addCompanyGroupMember,
  createCompanyGroup,
  listCompanyGroupMembers,
  listCompanyGroups,
  removeCompanyGroupMember,
  updateCompanyGroup
} from '@/api/company/group'
import { listCompanyUsers } from '@/api/company/user'
import type { CompanyGroup, CompanyGroupMember, CompanyUser } from '@/api/company/types'
import { keepStringId } from '@/utils/companyIds'
import { STATUS_LABELS } from '@/utils/companyNav'

const loading = ref(false)
const groups = ref<CompanyGroup[]>([])
const editVisible = ref(false)
const memberVisible = ref(false)
const form = ref({ id: '', name: '', description: '', status: 'ENABLED' })
const currentGroup = ref<CompanyGroup | null>(null)
const members = ref<CompanyGroupMember[]>([])
const allUsers = ref<CompanyUser[]>([])
const addUserId = ref('')

const load = async () => {
  loading.value = true
  try {
    groups.value = (await listCompanyGroups()).map(g => ({ ...g, id: keepStringId(g.id) }))
  } finally {
    loading.value = false
  }
}

const openCreate = () => {
  form.value = { id: '', name: '', description: '', status: 'ENABLED' }
  editVisible.value = true
}

const openEdit = (row: CompanyGroup) => {
  form.value = {
    id: keepStringId(row.id),
    name: row.name,
    description: row.description || '',
    status: row.status
  }
  editVisible.value = true
}

const submitGroup = async () => {
  if (!form.value.name) {
    ElMessage.warning('用户组名称不能为空')
    return
  }
  if (form.value.id) {
    await updateCompanyGroup(form.value.id, {
      name: form.value.name,
      description: form.value.description,
      status: form.value.status as any
    })
  } else {
    await createCompanyGroup({
      name: form.value.name,
      description: form.value.description,
      status: form.value.status as any
    })
  }
  ElMessage.success('已保存')
  editVisible.value = false
  await load()
}

const toggleStatus = (row: CompanyGroup) => {
  const next = row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
  const tip = next === 'DISABLED' ? '停用后该组授权立即不再生效。' : '启用后该组授权立即恢复生效。'
  ElMessageBox.confirm(tip, next === 'DISABLED' ? '停用用户组' : '启用用户组', {
    type: 'warning',
    autofocus: false
  }).then(async () => {
    await updateCompanyGroup(keepStringId(row.id), {
      name: row.name,
      description: row.description,
      status: next
    })
    ElMessage.success('已更新')
    await load()
  })
}

const openMembers = async (row: CompanyGroup) => {
  currentGroup.value = row
  memberVisible.value = true
  addUserId.value = ''
  members.value = (await listCompanyGroupMembers(keepStringId(row.id))).map(m => ({
    ...m,
    userId: keepStringId(m.userId)
  }))
  allUsers.value = (await listCompanyUsers()).map(u => ({ ...u, id: keepStringId(u.id) }))
}

const memberIds = computed(() => new Set(members.value.map(m => keepStringId(m.userId))))
const candidateUsers = computed(() =>
  allUsers.value.filter(u => !memberIds.value.has(keepStringId(u.id)))
)

const addMember = async () => {
  if (!currentGroup.value || !addUserId.value) {
    return
  }
  await addCompanyGroupMember(keepStringId(currentGroup.value.id), keepStringId(addUserId.value))
  ElMessage.success('已添加成员')
  await openMembers(currentGroup.value)
}

const removeMember = async (userId: string) => {
  if (!currentGroup.value) {
    return
  }
  await removeCompanyGroupMember(keepStringId(currentGroup.value.id), keepStringId(userId))
  ElMessage.success('已移除')
  await openMembers(currentGroup.value)
}

onMounted(load)
</script>

<template>
  <div class="company-admin-page" v-loading="loading">
    <div class="page-head">
      <span>用户组管理</span>
      <el-button type="primary" @click="openCreate">新建用户组</el-button>
    </div>
    <GridTable :show-pagination="false" :table-data="groups" class="admin-grid">
      <el-table-column prop="name" label="名称" min-width="160" />
      <el-table-column prop="description" label="描述" min-width="200" />
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ENABLED' ? 'success' : 'info'">
            {{ row.status === 'ENABLED' ? STATUS_LABELS.ENABLED : '已停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="memberCount" label="成员数" width="90" />
      <el-table-column label="操作" width="260" fixed="right">
        <template #default="{ row }">
          <el-button text @click="openEdit(row)">编辑</el-button>
          <el-button text @click="openMembers(row)">成员</el-button>
          <el-button text @click="toggleStatus(row)">
            {{ row.status === 'ENABLED' ? '停用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </GridTable>

    <el-dialog v-model="editVisible" :title="form.id ? '编辑用户组' : '新建用户组'" width="480px">
      <el-form label-width="80px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="submitGroup">保存</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="memberVisible" :title="`成员 · ${currentGroup?.name || ''}`" size="480px">
      <div class="member-add">
        <el-select v-model="addUserId" filterable placeholder="选择用户" style="flex: 1">
          <el-option
            v-for="u in candidateUsers"
            :key="u.id"
            :label="`${u.displayName} (${u.username})`"
            :value="u.id"
          />
        </el-select>
        <el-button type="primary" :disabled="!addUserId" @click="addMember">添加</el-button>
      </div>
      <el-table :data="members" style="margin-top: 16px">
        <el-table-column prop="username" label="用户名" />
        <el-table-column prop="displayName" label="显示名" />
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button text @click="removeMember(row.userId)">移除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>
  </div>
</template>

<style lang="less" scoped>
.company-admin-page {
  padding: 16px 24px;
}
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 20px;
  font-weight: 500;
  margin-bottom: 16px;
}
.member-add {
  display: flex;
  gap: 8px;
}
</style>
