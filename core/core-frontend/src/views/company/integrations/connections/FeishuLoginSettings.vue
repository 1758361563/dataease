<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus-secondary'
import request from '@/config/axios'
const form = ref({ enabled: false, baseUrl: '', tenantKey: '', appId: '' })
const busy = ref(false)
const error = ref('')
const base = computed(() => form.value.baseUrl.replace(/\/+$/, ''))
const callback = computed(() => (base.value ? `${base.value}/de2api/login/feishu/callback` : ''))
const entry = computed(() => (base.value ? `${base.value}/#/login?feishu=1` : ''))
async function load() {
  try {
    form.value = (await request.get({ url: '/company/bi/feishu/login-settings' })).data
  } catch {
    error.value = '登录配置读取失败'
  }
}
async function save() {
  busy.value = true
  try {
    form.value = (
      await request.post({ url: '/company/bi/feishu/login-settings', data: form.value })
    ).data
    ElMessage.success('飞书登录配置已保存')
  } finally {
    busy.value = false
  }
}
async function copy(value: string) {
  try {
    await navigator.clipboard.writeText(value)
    ElMessage.success('已复制')
  } catch {
    ElMessage.info('请选中地址复制')
  }
}
onMounted(load)
</script>
<template>
  <section class="login-settings">
    <div class="head">
      <h3>登录与工作台</h3>
      <el-switch v-model="form.enabled" active-text="启用飞书登录" />
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-form label-position="top">
      <el-form-item label="BI 访问地址"
        ><el-input v-model="form.baseUrl" placeholder="https://bi.example.com"
      /></el-form-item>
      <el-form-item label="飞书企业 tenant_key"
        ><el-input v-model="form.tenantKey" placeholder="仅允许该企业成员登录"
      /></el-form-item>
      <el-form-item label="授权回调地址"
        ><el-input :model-value="callback" readonly
          ><template #append
            ><el-button :disabled="!callback" @click="copy(callback)">复制</el-button></template
          ></el-input
        ></el-form-item
      >
      <el-form-item label="工作台主页（桌面 / 移动）"
        ><el-input :model-value="entry" readonly
          ><template #append
            ><el-button :disabled="!entry" @click="copy(entry)">复制</el-button></template
          ></el-input
        ></el-form-item
      >
    </el-form>
    <div class="footer">
      <el-tooltip
        content="复用上方应用凭据。在飞书开发者后台启用网页应用，配置主页、回调地址、权限和可用范围后发布。成员需在用户管理中绑定 BI 账号。"
        ><span class="help">配置说明 ⓘ</span></el-tooltip
      ><el-button type="primary" :loading="busy" @click="save">保存登录配置</el-button>
    </div>
  </section>
</template>
<style scoped>
.login-settings {
  max-width: 960px;
  padding: 24px;
  margin-top: 20px;
  background: #fff;
  border: 1px solid #e5e9f0;
  border-radius: 12px;
}

.head,
.footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.head {
  margin-bottom: 20px;
}

h3 {
  margin: 0;
  font-size: 16px;
}

.help {
  font-size: 13px;
  color: #8791a3;
}
</style>
