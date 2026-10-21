<script setup lang="ts">
import { ref, reactive } from 'vue'
import { ElMessage } from 'element-plus-secondary'
import request from '@/config/axios'
import { queryTreeApi } from '@/api/visualization/dataVisualization'
import { getDatasetTree } from '@/api/dataset'
import { parseDashboardMigration, flattenMigrationNodes } from '@/utils/dashboardMigration'
import router from '@/router'

const visible = ref(false)
const saving = ref(false)
const pack = ref(null)
const groups = ref<any[]>([])
const targets = ref<any[]>([])
const folders = ref<any[]>([])
const form = reactive({ name: '', pid: '0', datasetMapping: {} as Record<string, string> })

const open = async () => {
  pack.value = null
  groups.value = []
  targets.value = []
  form.name = ''
  form.pid = '0'
  form.datasetMapping = {}
  visible.value = true
  const nodes = await queryTreeApi({ busiFlag: 'dashboard', leaf: false, weight: 7 })
  folders.value = [
    { id: '0', name: '根目录' },
    ...flattenMigrationNodes(nodes as any, true).filter(folder => folder.id !== '0')
  ]
}

const chooseFile = async upload => {
  pack.value = null
  groups.value = []
  form.datasetMapping = {}
  if (!/\.(det2app|det2)$/i.test(upload.name) || upload.size > 20 * 1024 * 1024) {
    ElMessage.error('请选择不超过 20 MB 的 .DET2APP 或 .DET2 文件')
    return
  }
  try {
    const parsed = parseDashboardMigration(await upload.raw.text())
    groups.value = parsed.datasets
    if (groups.value.length) {
      const nodes = await getDatasetTree({})
      targets.value = flattenMigrationNodes(nodes as any)
    }
    pack.value = parsed.pack
    form.name = parsed.pack.name || '导入的仪表板'
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '模板读取失败')
  }
}

const save = async () => {
  if (saving.value || !pack.value) return
  if (
    !form.name.trim() ||
    form.name.trim().length > 64 ||
    groups.value.some(group => !form.datasetMapping[String(group.id)])
  ) {
    ElMessage.error('请填写1至64字的名称，并完成全部数据集映射')
    return
  }
  saving.value = true
  try {
    const result = await request.post({
      url: '/company/bi/migration/import',
      data: {
        template: pack.value,
        name: form.name.trim(),
        pid: form.pid,
        datasetMapping: form.datasetMapping
      }
    })
    if (result?.code !== 0 || !result.data) return
    ElMessage.success('已导入为草稿，请检查图表和筛选器后发布')
    visible.value = false
    await router.push({ path: '/dashboard', query: { resourceId: String(result.data) } })
  } catch {
    // API failures are displayed by the shared interceptor; retain the form for correction.
  } finally {
    saving.value = false
  }
}
defineExpose({ open })
</script>

<template>
  <el-dialog
    v-model="visible"
    title="应用模板 · 导入仪表板"
    width="640px"
    :close-on-click-modal="false"
  >
    <el-alert
      title="仅导入配置，不复制业务数据或连接凭证。已有数据集需映射到当前环境的数据集。"
      type="info"
      :closable="false"
    />
    <el-upload
      :auto-upload="false"
      :show-file-list="false"
      accept=".det2app,.det2,.DET2APP,.DET2"
      :on-change="chooseFile"
      class="migration-upload"
    >
      <el-button :disabled="saving">选择应用模板文件</el-button>
    </el-upload>
    <el-form v-if="pack" label-position="top">
      <el-form-item label="仪表板名称"
        ><el-input v-model="form.name" maxlength="64" :disabled="saving"
      /></el-form-item>
      <el-form-item label="目标文件夹">
        <el-select v-model="form.pid" :disabled="saving" style="width: 100%">
          <el-option
            v-for="folder in folders"
            :key="folder.id"
            :label="folder.name"
            :value="folder.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item
        v-for="group in groups"
        :key="String(group.id)"
        :label="`源数据集：${group.name}`"
      >
        <el-select
          v-model="form.datasetMapping[String(group.id)]"
          filterable
          :disabled="saving"
          placeholder="选择已有目标数据集"
          style="width: 100%"
        >
          <el-option
            v-for="target in targets"
            :key="target.id"
            :label="target.name"
            :value="target.id"
          />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button :disabled="saving" @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="!pack" @click="save"
        >导入为草稿</el-button
      >
    </template>
  </el-dialog>
</template>

<style scoped>
.migration-upload {
  margin: 16px 0;
}
</style>
