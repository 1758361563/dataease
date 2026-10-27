<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus-secondary'
import request from '@/config/axios'
import { useCompanyPermissionStoreWithOut } from '@/store/modules/companyPermission'
import { keepStringId, BOOTSTRAP_ADMIN_ID } from '@/utils/companyIds'

const canEdit = computed(
  () => keepStringId(useCompanyPermissionStoreWithOut().userId) === BOOTSTRAP_ADMIN_ID
)
const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const dirty = ref(false)
const runtime = ref({ state: 'STOPPED', running: false })
const connection = ref({ configured: false, hasAppSecret: false, hasScreenshotPassword: false })
const form = ref({
  appId: '',
  appSecret: '',
  screenshotUser: '',
  screenshotPassword: '',
  runtimeEnabled: false
})
const check = ref<any>(null)
const base = '/company/bi/feishu/connection'
const stateLabels: Record<string, string> = {
  RUNNING: '运行中',
  STARTING: '启动中',
  STOPPED: '未启动',
  UNAVAILABLE: '执行器不可用',
  MISSING_RUNTIME: '安装包缺少执行器'
}
const runtimeLabel = computed(() => stateLabels[runtime.value.state] || runtime.value.state)
async function load() {
  loading.value = true
  try {
    const [settings, status] = await Promise.all([
      request.get({ url: base }),
      request.get({ url: `${base}/runtime` })
    ])
    if (!settings?.data || !status?.data) throw new Error('连接配置读取失败')
    connection.value = settings.data
    form.value = {
      appId: settings.data.appId || '',
      appSecret: '',
      screenshotPassword: '',
      screenshotUser: settings.data.screenshotUser || '',
      runtimeEnabled: settings.data.runtimeEnabled === true
    }
    runtime.value = status.data
    dirty.value = false
  } finally {
    loading.value = false
  }
}
async function save() {
  if (saving.value || !canEdit.value) return
  saving.value = true
  try {
    const result = await request.post({ url: base, data: form.value })
    if (result?.code !== 0 || !result?.data) return
    check.value = null
    form.value.appSecret = ''
    form.value.screenshotPassword = ''
    await load()
    ElMessage.success('应用连接已保存，凭据已加密')
  } finally {
    saving.value = false
  }
}
async function test() {
  if (testing.value) return
  testing.value = true
  try {
    const result = await request.post({ url: `${base}/test` })
    if (result?.code !== 0 || !result?.data) return
    check.value = result.data
    runtime.value = (await request.get({ url: `${base}/runtime` })).data || runtime.value
    if (result.data.directoryConnected) ElMessage.success('飞书认证与通讯录读取通过')
    else ElMessage.warning('应用认证通过，通讯录权限检查未通过')
  } finally {
    testing.value = false
  }
}
onMounted(load)
</script>

<template>
  <div class="company-admin-page connection-page" v-loading="loading">
    <div class="page-head">
      <div>
        <h2>应用连接</h2>
        <p>统一管理飞书应用凭据，供订阅推送及后续数据同步功能复用。</p>
      </div>
      <el-button :disabled="dirty || saving || testing" @click="load">刷新状态</el-button>
    </div>
    <section class="connection-card">
      <div class="connection-title">
        <h3>飞书</h3>
        <el-tag :type="runtime.running ? 'success' : 'info'">{{ runtimeLabel }}</el-tag>
      </div>
      <p v-if="!connection.configured">
        尚未迁移应用连接。请填写原应用的 App ID 和 App Secret；已有订阅和收件人会保留。
      </p>
      <el-form
        label-position="top"
        :disabled="!canEdit || saving"
        @input="dirty = true"
        @change="dirty = true"
      >
        <div class="connection-grid">
          <el-form-item label="App ID"
            ><el-input v-model="form.appId" autocomplete="off"
          /></el-form-item>
          <el-form-item label="App Secret"
            ><el-input
              v-model="form.appSecret"
              type="password"
              show-password
              autocomplete="new-password"
              :placeholder="
                connection.hasAppSecret ? '已保存；留空保持原值' : '请输入飞书应用密钥'
              "
          /></el-form-item>
          <el-form-item label="BI 截图账号"
            ><el-input
              v-model="form.screenshotUser"
              autocomplete="off"
              placeholder="有目标仪表板查看权限的 BI 账号"
          /></el-form-item>
          <el-form-item label="BI 截图账号密码"
            ><el-input
              v-model="form.screenshotPassword"
              type="password"
              show-password
              autocomplete="new-password"
              :placeholder="
                connection.hasScreenshotPassword ? '已保存；留空保持原值' : '卡片推送时填写'
              "
          /></el-form-item>
        </div>
        <p class="credential-help">截图账号用于登录 BI 并访问订阅仪表板，与飞书账号无关。</p>
        <el-form-item label="启用飞书连接"
          ><el-switch v-model="form.runtimeEnabled" @change="dirty = true" /><span
            class="switch-note"
            >连接启用后，按订阅各自的启停状态和发送计划执行。</span
          ></el-form-item
        >
        <div v-if="canEdit" class="connection-actions">
          <el-button type="primary" :loading="saving" @click="save">保存连接</el-button>
          <el-button
            :loading="testing"
            :disabled="dirty || !connection.configured || !form.runtimeEnabled"
            @click="test"
            >验证连接</el-button
          >
        </div>
        <p v-else>当前账号可查看连接状态，应用凭据由系统初始管理员管理。</p>
      </el-form>
      <div v-if="check" class="connection-result" role="status">
        <p>应用认证：{{ check.appConnected ? '通过' : '未通过' }}</p>
        <p>
          通讯录读取：{{
            check.directoryConnected
              ? '通过'
              : `未通过（错误码 ${
                  check.directoryErrorCode || '未知'
                }），请核对应用通讯录权限和数据范围`
          }}
        </p>
        <p>
          截图能力：{{
            check.browserInstalled ? '可用' : '不可用，请检查 BI 安装包'
          }}。应用连接验证不检查具体仪表板的访问权限。
        </p>
      </div>
    </section>
  </div>
</template>

<style scoped lang="less">
.connection-page {
  padding: 24px;
  overflow: auto;
}
.page-head,
.connection-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
h2,
h3 {
  margin: 0 0 8px;
}
h2 {
  font-size: 20px;
  line-height: 28px;
  font-weight: 600;
}
h3 {
  font-size: 16px;
  font-weight: 600;
}
.page-head p {
  margin: 8px 0 0;
  font-size: 14px;
  font-weight: 400;
}
p {
  color: #646a73;
  line-height: 1.7;
}
.connection-card {
  max-width: 960px;
  padding: 24px;
  border: 1px solid #dee0e3;
  border-radius: 12px;
  background: #fff;
}
.connection-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 24px;
}
.switch-note {
  margin-left: 12px;
  color: #646a73;
}
.connection-actions {
  display: flex;
  gap: 12px;
}
.connection-result {
  margin-top: 20px;
  padding: 12px 16px;
  border-radius: 8px;
  background: #f5f6f7;
}
@media (max-width: 800px) {
  .connection-grid {
    grid-template-columns: 1fr;
  }
}
</style>
