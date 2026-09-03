<script lang="ts" setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus-secondary'
import GridTable from '@/components/grid-table/src/GridTable.vue'
import {
  changeCompanyUserRole,
  createCompanyUser,
  disableCompanyUser,
  enableCompanyUser,
  listCompanyUsers,
  resetCompanyUserPassword,
  updateCompanyUser
} from '@/api/company/user'
import type { CompanyAccountRole, CompanyUser, CompanyUserCreateBody } from '@/api/company/types'
import { BOOTSTRAP_ADMIN_ID, keepStringId } from '@/utils/companyIds'
import { ROLE_LABELS, STATUS_LABELS } from '@/utils/companyNav'
import { useCompanyPermissionStoreWithOut } from '@/store/modules/companyPermission'

const companyStore = useCompanyPermissionStoreWithOut()
const loading = ref(false)
const users = ref<CompanyUser[]>([])
const createVisible = ref(false)
const editVisible = ref(false)
const pwdVisible = ref(false)
const createForm = ref<CompanyUserCreateBody>({
  username: '',
  displayName: '',
  accountRole: 'NORMAL',
  status: 'ENABLED',
  password: ''
})
const editForm = ref({ id: '', username: '', displayName: '' })
const pwdForm = ref({ id: '', password: '', confirm: '' })

const isBootstrap = (row: CompanyUser) => keepStringId(row.id) === BOOTSTRAP_ADMIN_ID

const formatTime = (ms?: number) => {
  if (!ms) return '-'
  return new Date(ms).toLocaleString('zh-CN')
}

const load = async () => {
  loading.value = true
  try {
    users.value = (await listCompanyUsers()).map(u => ({ ...u, id: keepStringId(u.id) }))
  } finally {
    loading.value = false
  }
}

const resetCreate = () => {
  createForm.value = {
    username: '',
    displayName: '',
    accountRole: 'NORMAL',
    status: 'ENABLED',
    password: ''
  }
}

const submitCreate = async () => {
  if (!createForm.value.username || !createForm.value.password) {
    ElMessage.warning('用户名和临时密码不能为空')
    return
  }
  await createCompanyUser({ ...createForm.value })
  ElMessage.success('用户已创建')
  resetCreate()
  createVisible.value = false
  await load()
}

const openEdit = (row: CompanyUser) => {
  editForm.value = {
    id: keepStringId(row.id),
    username: row.username,
    displayName: row.displayName
  }
  editVisible.value = true
}

const submitEdit = async () => {
  await updateCompanyUser(editForm.value.id, {
    username: editForm.value.username,
    displayName: editForm.value.displayName
  })
  ElMessage.success('已保存')
  editVisible.value = false
  await load()
}

const afterSelfRoleLost = async (userId: string) => {
  if (userId !== companyStore.userId) {
    return
  }
  await companyStore.refreshCapabilities()
  if (!companyStore.hasCapability('USER_MANAGE')) {
    window.location.hash = '#/workbranch/index'
    window.location.reload()
  }
}

const onRoleChange = async (row: CompanyUser, accountRole: CompanyAccountRole) => {
  if (isBootstrap(row) && accountRole !== 'ADMIN') {
    ElMessage.warning('系统初始管理员不可禁用或降级。')
    await load()
    return
  }
  await changeCompanyUserRole(keepStringId(row.id), accountRole)
  ElMessage.success('角色已更新')
  await load()
  await afterSelfRoleLost(keepStringId(row.id))
}

const onDisable = (row: CompanyUser) => {
  if (isBootstrap(row)) {
    return
  }
  ElMessageBox.confirm('禁用后该用户当前登录会立即失效。', '禁用用户', {
    confirmButtonType: 'danger',
    type: 'warning',
    autofocus: false
  }).then(async () => {
    await disableCompanyUser(keepStringId(row.id))
    ElMessage.success('已禁用')
    await load()
  })
}

const onEnable = async (row: CompanyUser) => {
  await enableCompanyUser(keepStringId(row.id))
  ElMessage.success('已启用')
  await load()
}

const openPwd = (row: CompanyUser) => {
  pwdForm.value = { id: keepStringId(row.id), password: '', confirm: '' }
  pwdVisible.value = true
}

const submitPwd = async () => {
  if (!pwdForm.value.password || pwdForm.value.password !== pwdForm.value.confirm) {
    ElMessage.warning('请输入一致的新临时密码')
    return
  }
  await resetCompanyUserPassword(pwdForm.value.id, pwdForm.value.password)
  ElMessage.success('密码已重置')
  pwdForm.value = { id: '', password: '', confirm: '' }
  pwdVisible.value = false
}

onMounted(load)
</script>

<template>
  <div class="company-admin-page" v-loading="loading">
    <div class="page-head">
      <span>用户管理</span>
      <el-button type="primary" @click="createVisible = true">新建用户</el-button>
    </div>
    <GridTable :show-pagination="false" :table-data="users" class="admin-grid">
      <el-table-column prop="username" label="用户名" min-width="140" />
      <el-table-column prop="displayName" label="显示名" min-width="140" />
      <el-table-column label="角色" width="160">
        <template #default="{ row }">
          <el-tooltip
            :disabled="!isBootstrap(row)"
            content="系统初始管理员不可禁用或降级。"
            placement="top"
          >
            <span>
              <el-select
                :model-value="row.accountRole"
                :disabled="isBootstrap(row)"
                @change="(val: CompanyAccountRole) => onRoleChange(row, val)"
              >
                <el-option
                  v-for="(label, value) in ROLE_LABELS"
                  :key="value"
                  :label="label"
                  :value="value"
                />
              </el-select>
            </span>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ENABLED' ? 'success' : 'info'">{{
            STATUS_LABELS[row.status]
          }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" min-width="170">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="280" fixed="right">
        <template #default="{ row }">
          <el-button text @click="openEdit(row)">编辑</el-button>
          <template v-if="row.status === 'ENABLED'">
            <el-tooltip
              :disabled="!isBootstrap(row)"
              content="系统初始管理员不可禁用或降级。"
              placement="top"
            >
              <span>
                <el-button text :disabled="isBootstrap(row)" @click="onDisable(row)">
                  禁用
                </el-button>
              </span>
            </el-tooltip>
          </template>
          <el-button v-else text @click="onEnable(row)">启用</el-button>
          <el-button text @click="openPwd(row)">重置密码</el-button>
        </template>
      </el-table-column>
    </GridTable>
    <el-tooltip
      v-if="users.some(isBootstrap)"
      content="系统初始管理员不可禁用或降级。"
      placement="top"
    >
      <span class="hint">uid=1 为系统初始管理员，禁用与降级按钮已锁定。</span>
    </el-tooltip>

    <el-dialog v-model="createVisible" title="新建用户" width="480px" @closed="resetCreate">
      <el-form label-width="100px">
        <el-form-item label="用户名" required>
          <el-input v-model="createForm.username" />
        </el-form-item>
        <el-form-item label="显示名">
          <el-input v-model="createForm.displayName" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="createForm.accountRole">
            <el-option
              v-for="(label, value) in ROLE_LABELS"
              :key="value"
              :label="label"
              :value="value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="临时密码" required>
          <el-input
            v-model="createForm.password"
            type="password"
            show-password
            autocomplete="new-password"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="editVisible" title="编辑用户" width="480px">
      <el-form label-width="100px">
        <el-form-item label="用户名">
          <el-input v-model="editForm.username" />
        </el-form-item>
        <el-form-item label="显示名">
          <el-input v-model="editForm.displayName" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="pwdVisible" title="重置密码" width="480px">
      <el-form label-width="120px">
        <el-form-item label="新临时密码">
          <el-input
            v-model="pwdForm.password"
            type="password"
            show-password
            autocomplete="new-password"
          />
        </el-form-item>
        <el-form-item label="再次确认">
          <el-input
            v-model="pwdForm.confirm"
            type="password"
            show-password
            autocomplete="new-password"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pwdVisible = false">取消</el-button>
        <el-button type="primary" @click="submitPwd">重置</el-button>
      </template>
    </el-dialog>
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
.hint {
  display: inline-block;
  margin-top: 12px;
  color: #646a73;
  font-size: 12px;
}
</style>
