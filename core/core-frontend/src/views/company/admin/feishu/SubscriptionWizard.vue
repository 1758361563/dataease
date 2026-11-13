<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import request from '@/config/axios'
import { ElMessage } from 'element-plus-secondary'

const props = defineProps<{ modelValue: boolean; form: any; saving?: boolean }>()
const emit = defineEmits(['update:modelValue', 'update:form', 'save'])
const visible = computed({ get: () => props.modelValue, set: v => emit('update:modelValue', v) })
const form = ref(JSON.parse(JSON.stringify(props.form)))
watch(
  () => props.form,
  value => {
    if (JSON.stringify(value) !== JSON.stringify(form.value))
      form.value = JSON.parse(JSON.stringify(value))
  },
  { deep: true }
)
watch(form, value => emit('update:form', value), { deep: true })
const contentError = ref(false)
const step = ref(0)
const loading = ref(false)
const previewing = ref(false)
const error = ref('')
const dashboards = ref<any[]>([])
const cards = ref<any[]>([])
const fields = ref<any[]>([])
const filters = ref<any[]>([])
const images = ref<any[]>([])
let generation = 0
const selectedCards = computed(() =>
  (form.value.content?.chartIds || []).map(
    id => cards.value.find(c => c.id === id) || { id, name: '卡片已移除，请重新选择' }
  )
)
const previewKey = computed(() =>
  JSON.stringify([form.value.dashboardId, form.value.content, filters.value])
)
watch(
  previewKey,
  () => {
    images.value = []
    form.value.fixedFilterJson = JSON.stringify({ version: 2, items: filters.value })
  },
  { deep: true }
)

async function loadContent(clear = false) {
  const current = ++generation
  if (clear) {
    form.value.content.chartIds = []
    filters.value = []
    images.value = []
  }
  cards.value = []
  fields.value = []
  error.value = ''
  contentError.value = false
  if (!form.value.dashboardId) return
  loading.value = true
  try {
    const res = await request.get({ url: `/company/bi/feishu/content/${form.value.dashboardId}` })
    if (current !== generation) return
    cards.value = res.data.cards
    fields.value = res.data.filters
    form.value.content.dashboardName = res.data.name
  } catch (e) {
    if (current === generation) {
      contentError.value = true
      error.value = '内容加载失败，请重试'
    }
  } finally {
    if (current === generation) loading.value = false
  }
}
watch(visible, async open => {
  if (!open) {
    generation++
    return
  }
  step.value = 0
  images.value = []
  error.value = ''
  form.value.content ||= {
    version: 1,
    mode: 'CARDS',
    chartIds: form.value.chartId ? [form.value.chartId] : []
  }
  try {
    filters.value = JSON.parse(form.value.fixedFilterJson || '{}').items || []
  } catch {
    error.value = '旧订阅筛选配置无法读取，请重新设置'
    filters.value = []
  }
  try {
    dashboards.value = (await request.get({ url: '/company/bi/feishu/content/dashboards' })).data
  } catch {
    error.value = '仪表板列表加载失败，请重新打开'
  }
  await loadContent()
})

function validateContent() {
  if (!form.value.dashboardId || loading.value || contentError.value) return false
  if (
    form.value.content.mode === 'CARDS' &&
    (!selectedCards.value.length ||
      selectedCards.value.length > 12 ||
      selectedCards.value.some(c => !cards.value.some(item => item.id === c.id)))
  ) {
    ElMessage.warning('请选择 1 至 12 张卡片')
    return false
  }
  return true
}
function move(index: number, offset: number) {
  const ids = form.value.content.chartIds
  if (index + offset < 0 || index + offset >= ids.length) return
  ;[ids[index], ids[index + offset]] = [ids[index + offset], ids[index]]
}
function addFilter() {
  const field = fields.value.find(field => !filters.value.some(f => f.id === field.id))
  if (field)
    filters.value.push({
      id: field.id,
      name: field.name,
      value:
        field.multiple ||
        ['7', '22'].includes(field.displayType) ||
        (field.displayType === '8' && field.conditionType !== 0)
          ? []
          : '',
      dateRule: 'fixed'
    })
}
function changeField(item: any) {
  const field = fields.value.find(f => f.id === item.id)
  item.name = field.name
  item.value = field.multiple || field.displayType === '7' ? [] : ''
  item.dateRule = 'fixed'
}
function fieldOf(item: any) {
  return fields.value.find(f => f.id === item.id) || {}
}
async function preview() {
  if (!validateContent() || previewing.value) return
  if (
    filters.value.some(
      item =>
        !fields.value.some(field => field.id === item.id) ||
        (item.dateRule === 'fixed' &&
          (item.value === '' ||
            item.value == null ||
            (Array.isArray(item.value) && !item.value.length)))
    )
  ) {
    ElMessage.warning('请补全筛选条件，或移除不使用的条件')
    return
  }
  const key = previewKey.value
  previewing.value = true
  error.value = ''
  try {
    const res = await request.post({
      url: '/company/bi/feishu/content/preview',
      timeout: 605000,
      data: {
        dashboardId: form.value.dashboardId,
        content: form.value.content,
        filters: filters.value
      }
    })
    if (key === previewKey.value && visible.value) images.value = res.data.images
  } catch {
    error.value = '预览生成失败，请检查上方错误提示后重试'
  } finally {
    previewing.value = false
  }
}
function next() {
  if (step.value === 0 && !validateContent()) return
  if (step.value === 1 && !images.value.length) {
    ElMessage.warning('请先生成预览，确认发送内容')
    return
  }
  step.value++
}
</script>

<template>
  <el-drawer
    v-model="visible"
    class="subscription-wizard"
    :title="form.id ? '编辑订阅' : '创建订阅'"
    size="min(960px, 96vw)"
    :close-on-click-modal="false"
  >
    <div class="wizard-steps">
      <span
        v-for="(label, index) in ['选择内容', '筛选与预览', '发送设置']"
        :key="label"
        :class="{ active: step === index }"
        >{{ index + 1 }} · {{ label }}</span
      >
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-form v-show="step === 0" label-position="top" class="wizard-form" v-loading="loading">
      <el-form-item label="订阅内容">
        <el-radio-group v-model="form.content.mode">
          <el-radio-button value="DASHBOARD" label="DASHBOARD">整个仪表板</el-radio-button>
          <el-radio-button value="CARDS" label="CARDS">指定卡片</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="仪表板" required>
        <el-select
          v-model="form.dashboardId"
          filterable
          placeholder="选择或搜索仪表板"
          @change="loadContent(true)"
        >
          <el-option
            v-for="item in dashboards"
            :key="item.id"
            :label="item.name"
            :value="item.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item v-if="form.content.mode === 'CARDS'" label="卡片" required>
        <el-select
          v-model="form.content.chartIds"
          filterable
          multiple
          :multiple-limit="12"
          placeholder="选择或搜索卡片"
          :disabled="!form.dashboardId"
        >
          <el-option v-for="item in cards" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
      </el-form-item>
      <div v-if="form.content.mode === 'CARDS'" class="card-order">
        <div v-for="(card, index) in selectedCards" :key="card.id" class="card-order-row">
          <span class="order-index">{{ index + 1 }}</span
          ><span class="card-name">{{ card.name }}</span>
          <el-button text :disabled="index === 0" @click="move(index, -1)" aria-label="上移"
            >↑</el-button
          >
          <el-button
            text
            :disabled="index === selectedCards.length - 1"
            @click="move(index, 1)"
            aria-label="下移"
            >↓</el-button
          >
        </div>
      </div>
    </el-form>
    <section v-show="step === 1" class="wizard-preview">
      <div class="preview-toolbar">
        <span>筛选条件</span
        ><el-button text :disabled="filters.length >= fields.length" @click="addFilter"
          >添加条件</el-button
        >
      </div>
      <div v-for="(item, index) in filters" :key="index" class="filter-row">
        <el-select v-model="item.id" filterable @change="changeField(item)"
          ><el-option
            v-for="field in fields"
            :key="field.id"
            :value="field.id"
            :label="field.name"
            :disabled="filters.some((f, i) => i !== index && f.id === field.id)"
        /></el-select>
        <template v-if="fieldOf(item).displayType === '7'">
          <el-select v-model="item.dateRule"
            ><el-option label="固定范围" value="fixed" /><el-option
              label="昨天"
              value="yesterday" /><el-option label="最近 7 天（至昨天）" value="last7" /><el-option
              label="月初至昨天"
              value="monthToYesterday"
          /></el-select>
          <el-date-picker
            v-if="item.dateRule === 'fixed'"
            v-model="item.value"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
          />
        </template>
        <el-date-picker
          v-else-if="fieldOf(item).displayType === '1'"
          v-model="item.value"
          value-format="YYYY-MM-DD"
          placeholder="选择日期"
        />
        <template v-else-if="fieldOf(item).displayType === '22'">
          <el-input-number v-model="item.value[0]" :controls="false" placeholder="最小值" />
          <el-input-number v-model="item.value[1]" :controls="false" placeholder="最大值" />
        </template>
        <template v-else-if="fieldOf(item).displayType === '8'">
          <template v-if="fieldOf(item).conditionType !== 0">
            <el-input v-model="item.value[0]" placeholder="第一个条件值" />
            <el-input v-model="item.value[1]" placeholder="第二个条件值" />
          </template>
          <el-input v-else v-model="item.value" placeholder="输入条件值" />
        </template>
        <el-select
          v-else
          v-model="item.value"
          filterable
          allow-create
          default-first-option
          :multiple="fieldOf(item).multiple"
          placeholder="选择或输入筛选值"
          ><el-option
            v-for="option in fieldOf(item).valueSource || []"
            :key="option.value ?? option"
            :value="option.value ?? option"
            :label="option.label || option.value || String(option)"
        /></el-select>
        <el-button text @click="filters.splice(index, 1)" aria-label="移除筛选条件">×</el-button>
      </div>
      <p v-if="!filters.length" class="empty-filters">使用仪表板默认筛选条件</p>
      <el-button :loading="previewing" @click="preview">{{
        images.length ? '重新生成预览' : '生成预览'
      }}</el-button>
      <div class="preview-images" v-loading="previewing">
        <article v-for="(image, index) in images" :key="index">
          <span>{{ image.name }}</span
          ><el-image
            :src="image.url"
            :preview-src-list="images.map(i => i.url)"
            :initial-index="index"
            fit="contain"
          />
        </article>
      </div>
    </section>
    <div v-show="step === 2"><slot /></div>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button v-if="step > 0" @click="step--">上一步</el-button>
      <el-button v-if="step < 2" type="primary" :disabled="previewing || loading" @click="next"
        >下一步</el-button
      >
      <el-button v-else type="primary" :loading="saving" @click="emit('save')">保存订阅</el-button>
    </template>
  </el-drawer>
</template>

<style scoped lang="less">
.wizard-steps {
  display: flex;
  gap: 24px;
  padding: 0 0 20px;
  color: #86909c;
}
.wizard-steps .active {
  color: var(--el-color-primary, #596fb0);
  font-weight: 600;
}
.wizard-form {
  padding-top: 20px;
}
.wizard-form :deep(.ed-select) {
  width: 100%;
}
.card-order {
  overflow: hidden;
  border: 1px solid #e8ebf0;
  border-radius: 10px;
}
.card-order-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 14px;
  border-bottom: 1px solid #f0f2f5;
}
.order-index {
  color: #86909c;
}
.card-name {
  flex: 1;
}
.preview-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 16px 0;
}
.filter-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}
.filter-row :deep(.ed-select) {
  flex: 1;
  min-width: 170px;
}
.empty-filters {
  margin-bottom: 20px;
  color: #86909c;
}
.preview-images {
  display: grid;
  gap: 20px;
  margin-top: 20px;
}
.preview-images article {
  display: grid;
  gap: 8px;
  padding: 12px;
  border: 1px solid #e8ebf0;
  border-radius: 10px;
}
.preview-images :deep(.ed-image) {
  width: 100%;
}
</style>
