<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { cloneDeep } from 'lodash-es'
import { ElMessage } from 'element-plus-secondary'
import { queryDatasetPreview, type PreviewQuery, type PreviewRule } from '@/api/dataset'
import { matchesField, pasteValues, validateRules, fieldOriginalName } from './previewState'
import DatasetIcon from './DatasetIcon.vue'

const props = defineProps<{
  modelValue: boolean
  fields: any[]
  rules: PreviewRule[]
  logic: 'and' | 'or'
  query: () => PreviewQuery
  total?: number | null
  initialField?: string
}>()
const emit = defineEmits(['update:modelValue', 'apply'])
const draft = ref<PreviewRule[]>([])
const draftLogic = ref<'and' | 'or'>('and')
const activeId = ref('')
const fieldSearch = ref('')
const valueSearch = ref('')
const options = ref<string[]>([])
const loading = ref(false)
const candidateError = ref('')
const more = ref(false)
const candidatePage = ref(1)
const tab = ref('values')
const inputTab = ref('select')
const pasted = ref('')
const estimate = ref<number | null>(null)
const estimating = ref(false)
let valueSerial = 0
let countSerial = 0
let searchTimer: ReturnType<typeof setTimeout>
let countTimer: ReturnType<typeof setTimeout>
const field = computed(() => props.fields.find(f => String(f.id) === activeId.value))
const shownFields = computed(() => props.fields.filter(f => matchesField(f, fieldSearch.value)))
const current = computed(() => draft.value.find(r => r.fieldId === activeId.value))
const noValue = (op: string) => ['null', 'not_null', 'empty', 'not_empty'].includes(op)
const configured = computed(() =>
  draft.value.filter(
    r =>
      !['in', 'not in'].includes(r.operator) ||
      noValue(r.operator) ||
      r.values.some(v => v !== '') ||
      (['in', 'not in'].includes(r.operator) && r.values.length)
  )
)
const fieldName = (id: string) => props.fields.find(f => String(f.id) === id)?.name || '字段已失效'
const typeLabel = (type: number) =>
  ['文本', '日期', '整数', '小数', '数值', '位置', '', 'URL'][type] || '字段'
const badge = (f: any) => ([2, 3, 4].includes(f?.deType) ? '#' : f?.deType === 1 ? '◷' : 'Aa')
const operators = computed(() => {
  const result = [
    { value: 'eq', label: '等于' },
    { value: 'not_eq', label: '不等于' }
  ]
  if ([1, 2, 3, 4].includes(field.value?.deType))
    result.push(
      { value: 'between', label: '范围（包含边界）' },
      { value: 'gt', label: '大于' },
      { value: 'ge', label: '大于等于' },
      { value: 'lt', label: '小于' },
      { value: 'le', label: '小于等于' }
    )
  else
    result.push(
      { value: 'like', label: '包含' },
      { value: 'not like', label: '不包含' },
      { value: 'empty', label: '空字符串' },
      { value: 'not_empty', label: '非空字符串' }
    )
  result.push(
    { value: 'null', label: '为空（NULL）' },
    { value: 'not_null', label: '不为空（NULL）' }
  )
  return result
})
const ensureRule = (): PreviewRule => {
  if (current.value) return current.value
  const rule: PreviewRule = {
    fieldId: activeId.value,
    deType: field.value?.deType,
    operator: 'in',
    values: []
  }
  draft.value.push(rule)
  return draft.value[draft.value.length - 1]
}
const loadValues = async (reset = true) => {
  clearTimeout(searchTimer)
  if (!activeId.value || !props.modelValue) return
  const ticket = ++valueSerial
  if (reset) {
    candidatePage.value = 1
    options.value = []
  }
  loading.value = true
  candidateError.value = ''
  try {
    const result = await queryDatasetPreview(
      {
        ...props.query(),
        rules: [],
        columnIds: undefined,
        valueFieldId: activeId.value,
        searchText: valueSearch.value,
        page: candidatePage.value,
        size: 100
      },
      'previewValues'
    )
    if (ticket !== valueSerial) return
    const resultField = result.data.fields?.[0]
    const data = result.data.data || []
    options.value = [
      ...new Set([
        ...options.value,
        ...data
          .map(row => row[resultField?.dataeaseName])
          .filter(v => v !== null && v !== undefined)
          .map(String)
      ])
    ]
    more.value = data.length === 100
  } catch {
    if (ticket === valueSerial) candidateError.value = '候选值加载失败，请重试'
  } finally {
    if (ticket === valueSerial) loading.value = false
  }
}
const loadMore = () => {
  candidatePage.value++
  loadValues(false)
}
const activate = (id: string) => {
  ++valueSerial
  activeId.value = id
  valueSearch.value = ''
  pasted.value = ''
  inputTab.value = 'select'
  tab.value =
    current.value && !['in', 'not in'].includes(current.value.operator) ? 'condition' : 'values'
  loadValues()
}
const setTab = (next: string) => {
  tab.value = next
  const rule = ensureRule()
  rule.operator = next === 'values' ? 'in' : 'eq'
  rule.values = next === 'values' ? [] : ['']
}
const updateSelection = (values: string[]) => {
  ensureRule().values = values
}
const changeOperator = () => {
  const rule = ensureRule()
  rule.values = rule.operator === 'between' ? ['', ''] : ['']
}
const setOperator = (op: string) => {
  ensureRule().operator = op
}
const toggleAll = (checked: boolean) => {
  const rule = ensureRule()
  rule.values = checked
    ? [...new Set([...rule.values, ...options.value])]
    : rule.values.filter(v => !options.value.includes(v))
}
const allChecked = computed(
  () => options.value.length > 0 && options.value.every(v => current.value?.values.includes(v))
)
const someChecked = computed(
  () => !allChecked.value && options.value.some(v => current.value?.values.includes(v))
)
const removeRule = (id: string) => {
  draft.value = draft.value.filter(r => r.fieldId !== id)
}
const removeValue = (id: string, value: string) => {
  const rule = draft.value.find(r => r.fieldId === id)
  if (rule) rule.values = rule.values.filter(v => v !== value)
}
const addPasted = () => {
  const rule = ensureRule()
  const values = [...new Set([...rule.values, ...pasteValues(pasted.value)])]
  if (values.length > 1000) {
    ElMessage.error('每条规则最多1000个值')
    return
  }
  rule.values = values
  inputTab.value = 'select'
  pasted.value = ''
}
const count = async () => {
  const ticket = ++countSerial
  if (!props.modelValue || validateRules(configured.value, props.fields)) {
    estimate.value = null
    estimating.value = false
    return
  }
  estimating.value = true
  try {
    const result = await queryDatasetPreview(
      {
        ...props.query(),
        rules: cloneDeep(configured.value),
        logic: draftLogic.value,
        columnIds: undefined,
        page: 1
      },
      'previewCount'
    )
    if (ticket === countSerial) estimate.value = Number(result.total)
  } catch {
    if (ticket === countSerial) estimate.value = null
  } finally {
    if (ticket === countSerial) estimating.value = false
  }
}
const apply = () => {
  const message = validateRules(configured.value, props.fields)
  if (message) {
    ElMessage.error(message)
    return
  }
  emit('apply', cloneDeep(configured.value), draftLogic.value)
  emit('update:modelValue', false)
}
const close = () => emit('update:modelValue', false)
const summary = (rule: PreviewRule) => {
  if (['in', 'not in'].includes(rule.operator))
    return rule.operator === 'in' ? '包含所选值' : '排除所选值'
  const labels = {
    eq: '等于',
    not_eq: '不等于',
    like: '包含',
    'not like': '不包含',
    gt: '大于',
    ge: '大于等于',
    lt: '小于',
    le: '小于等于',
    between: '范围',
    null: '为空（NULL）',
    not_null: '不为空（NULL）',
    empty: '空字符串',
    not_empty: '非空字符串'
  }
  return `${labels[rule.operator] || rule.operator}${
    noValue(rule.operator) ? '' : ' ' + rule.values.join(' ～ ')
  }`
}
watch(
  () => props.modelValue,
  open => {
    if (open) {
      draft.value = cloneDeep(props.rules)
      draftLogic.value = props.logic
      fieldSearch.value = ''
      estimate.value = null
      const id =
        props.initialField && props.fields.some(f => String(f.id) === props.initialField)
          ? props.initialField
          : draft.value[0]?.fieldId || String(props.fields[0]?.id || '')
      activate(id)
      clearTimeout(countTimer)
      countTimer = setTimeout(count, 100)
    } else {
      ++valueSerial
      ++countSerial
      clearTimeout(searchTimer)
      clearTimeout(countTimer)
    }
  }
)
watch(valueSearch, () => {
  ++valueSerial
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => loadValues(), 300)
})
watch(
  [draft, draftLogic],
  () => {
    ++countSerial
    estimate.value = null
    estimating.value = false
    clearTimeout(countTimer)
    if (props.modelValue) countTimer = setTimeout(count, 600)
  },
  { deep: true }
)
onBeforeUnmount(() => {
  ++valueSerial
  ++countSerial
  clearTimeout(searchTimer)
  clearTimeout(countTimer)
})
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    class="dataset-filter-studio"
    width="min(980px, 94vw)"
    modal-class="dataset-centered-overlay"
    append-to-body
    :show-close="false"
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <template #header
      ><div class="studio-header">
        <div>
          <h2>筛选数据</h2>
        </div>
        <button class="studio-icon-button" title="关闭筛选" aria-label="关闭筛选" @click="close">
          <DatasetIcon name="close" />
        </button></div
    ></template>
    <div class="studio-layout">
      <aside class="studio-fields">
        <div class="studio-field-search">
          <el-input v-model="fieldSearch" clearable placeholder="查找字段" aria-label="查找筛选字段"
            ><template #prefix><DatasetIcon name="search" /></template
          ></el-input>
        </div>
        <div class="studio-field-scroll">
          <button
            v-for="item in shownFields"
            :key="item.id"
            class="studio-field"
            :class="{ active: activeId === String(item.id) }"
            :title="[item.name, fieldOriginalName(item)].filter(Boolean).join(' · ')"
            @click="activate(String(item.id))"
          >
            <span class="studio-type" :class="{ numeric: [2, 3, 4].includes(item.deType) }">{{
              badge(item)
            }}</span
            ><span class="studio-field-name"
              >{{ item.name
              }}<small v-if="fieldOriginalName(item)">{{ fieldOriginalName(item) }}</small></span
            ><i v-if="configured.some(r => r.fieldId === String(item.id))"></i>
          </button>
          <p v-if="!shownFields.length" class="studio-empty">没有匹配的字段</p>
        </div>
      </aside>
      <section class="studio-editor">
        <div class="studio-rule-toolbar">
          <div class="studio-logic">
            <label>规则关系</label
            ><el-select v-model="draftLogic"
              ><el-option label="全部满足 · AND" value="and" /><el-option
                label="任一满足 · OR"
                value="or"
            /></el-select>
          </div>
          <span class="studio-inline-count"
            >{{ estimating ? '…' : estimate ?? '—' }}/{{ total ?? '—' }}</span
          >
        </div>
        <details v-if="configured.length" class="studio-rule-details">
          <summary>已选规则 {{ configured.length }}</summary>
          <div class="studio-rules-scroll">
            <p v-if="!configured.length" class="studio-summary-empty">暂无规则</p>
            <article v-for="rule in configured" :key="rule.fieldId" class="studio-rule">
              <div class="studio-rule-title">
                <button @click="activate(rule.fieldId)">{{ fieldName(rule.fieldId) }}</button
                ><button
                  class="studio-icon-button"
                  :aria-label="`删除${fieldName(rule.fieldId)}规则`"
                  @click="removeRule(rule.fieldId)"
                >
                  <DatasetIcon name="close" />
                </button>
              </div>
              <p>{{ summary(rule) }}</p>
              <div v-if="['in', 'not in'].includes(rule.operator)" class="studio-rule-values">
                <span v-for="value in rule.values" :key="value" :title="value"
                  >{{ value === '' ? '（空字符串）' : value
                  }}<button
                    :aria-label="`取消选择${value}`"
                    @click="removeValue(rule.fieldId, value)"
                  >
                    ×
                  </button></span
                >
              </div>
            </article>
          </div>
        </details>
        <template v-if="field">
          <div class="studio-current">
            <strong>{{ field.name }}</strong
            ><code v-if="fieldOriginalName(field)" :title="fieldOriginalName(field)">{{
              fieldOriginalName(field)
            }}</code
            ><span>{{ typeLabel(field.deType) }}</span>
          </div>
          <div class="studio-tabs">
            <button :class="{ active: tab === 'values' }" @click="setTab('values')">选择值</button
            ><button :class="{ active: tab === 'condition' }" @click="setTab('condition')">
              条件匹配
            </button>
          </div>
          <template v-if="tab === 'values'">
            <div class="studio-value-tools">
              <div class="studio-subtabs">
                <button :class="{ active: inputTab === 'select' }" @click="inputTab = 'select'">
                  当前值</button
                ><button :class="{ active: inputTab === 'paste' }" @click="inputTab = 'paste'">
                  批量粘贴
                </button>
              </div>
              <div class="studio-segment">
                <button
                  :class="{ active: current?.operator !== 'not in' }"
                  @click="setOperator('in')"
                >
                  包含</button
                ><button
                  :class="{ active: current?.operator === 'not in' }"
                  @click="setOperator('not in')"
                >
                  排除
                </button>
              </div>
            </div>
            <template v-if="inputTab === 'select'">
              <el-input
                v-model="valueSearch"
                clearable
                placeholder="搜索当前字段的值"
                aria-label="搜索候选值"
                @keyup.enter="loadValues()"
                ><template #prefix><DatasetIcon name="search" /></template
              ></el-input>
              <div class="studio-select-all">
                <el-checkbox
                  :model-value="allChecked"
                  :indeterminate="someChecked"
                  :disabled="!options.length"
                  @change="toggleAll"
                  >选中已加载值</el-checkbox
                ><span>{{ options.length }} 项{{ more ? '+' : '' }}</span>
              </div>
              <div v-loading="loading" class="studio-values-scroll">
                <div v-if="candidateError" class="studio-candidate-error">
                  <span>{{ candidateError }}</span
                  ><el-button text @click="loadValues()">重试</el-button>
                </div>
                <el-checkbox-group
                  v-else
                  :model-value="current?.values || []"
                  @update:model-value="updateSelection"
                  ><el-checkbox v-for="value in options" :key="value" :label="value"
                    ><span :title="value">{{
                      value === '' ? '（空字符串）' : value
                    }}</span></el-checkbox
                  ></el-checkbox-group
                >
                <p v-if="!loading && !candidateError && !options.length" class="studio-empty">
                  {{
                    valueSearch
                      ? '没有匹配的候选值，可调整搜索或批量粘贴'
                      : '当前字段没有非 NULL 候选值，可使用条件匹配筛选空值'
                  }}
                </p>
                <el-button
                  v-if="more && !candidateError"
                  class="studio-load-more"
                  text
                  :loading="loading"
                  @click="loadMore"
                  >加载更多候选值</el-button
                >
              </div>
            </template>
            <div v-else class="studio-paste">
              <p>每行一个值，最多1000个；重复值自动合并。</p>
              <el-input
                v-model="pasted"
                type="textarea"
                :rows="9"
                placeholder="在此粘贴要筛选的值，每行一个"
              /><el-button secondary @click="addPasted">加入已选值</el-button>
            </div>
            <div class="studio-selection-foot">
              <span
                >已选 <strong>{{ current?.values.length || 0 }}</strong> 项</span
              ><button v-if="current?.values.length" @click="updateSelection([])">
                清空当前选择
              </button>
            </div>
          </template>
          <div v-else class="studio-condition">
            <label>筛选条件</label
            ><el-select
              :model-value="current?.operator || 'eq'"
              @update:model-value="setOperator"
              @change="changeOperator"
              ><el-option
                v-for="op in operators"
                :key="op.value"
                :label="op.label"
                :value="op.value"
            /></el-select>
            <template v-if="current && !noValue(current.operator)"
              ><div
                v-for="position in current.operator === 'between' ? 2 : 1"
                :key="position"
                class="studio-condition-input"
              >
                <label>{{
                  current.operator === 'between'
                    ? position === 1
                      ? '起始值（含）'
                      : '结束值（含）'
                    : '筛选值'
                }}</label
                ><el-date-picker
                  v-if="field.deType === 1"
                  v-model="current.values[position - 1]"
                  type="datetime"
                  value-format="YYYY-MM-DD HH:mm:ss"
                  :placeholder="position === 1 ? '选择开始时间' : '选择结束时间'"
                /><el-input
                  v-else
                  v-model="current.values[position - 1]"
                  :placeholder="[2, 3, 4].includes(field.deType) ? '输入数值' : '输入匹配内容'"
                /></div
            ></template>
          </div>
        </template>
        <p v-else class="studio-empty">暂无可筛选字段，请先准备好数据来源。</p>
      </section>
    </div>
    <template #footer
      ><div class="studio-footer">
        <div>
          <el-button @click="close">取消</el-button
          ><el-button type="primary" @click="apply">应用筛选</el-button>
        </div>
      </div></template
    >
  </el-dialog>
</template>

<style lang="less">
.dataset-filter-studio.ed-dialog {
  --studio-accent: #5b70a8;
  font-family: var(--de-custom_font, 'Segoe UI'), 'Microsoft YaHei', sans-serif;
  padding: 0;
  border-radius: 12px;
  overflow: hidden;
  color: #35445d;
  box-shadow: 0 24px 80px #25344a26;
  margin-bottom: 24px;
  .ed-dialog__header {
    padding: 0;
    margin: 0;
  }
  .ed-dialog__body {
    padding: 0;
  }
  .ed-dialog__footer {
    padding: 0;
  }
  button {
    font-family: inherit;
    cursor: pointer;
  }
  .ed-input__wrapper,
  .ed-select .ed-input__wrapper {
    border-radius: 5px;
    box-shadow: 0 0 0 1px #dfe5ee inset;
  }
  .ed-input__inner {
    font-size: 12px;
  }
  .ed-button {
    border-radius: 5px;
    font-size: 12px;
    height: 34px;
  }
  .ed-button--primary {
    background: var(--studio-accent);
    border-color: var(--studio-accent);
  }
  .ed-checkbox__input.is-checked .ed-checkbox__inner,
  .ed-checkbox__input.is-indeterminate .ed-checkbox__inner {
    background: var(--studio-accent);
    border-color: var(--studio-accent);
  }
  .studio-header {
    display: flex;
    justify-content: space-between;
    align-items: flex-start;
    padding: 18px 24px;
    border-bottom: 1px solid #e3e8f0;
    h2 {
      font-size: 19px;
      font-weight: 600;
      margin: 0;
      color: #293c59;
    }
    p {
      font-size: 12px;
      color: #94a0b3;
      margin: 0;
    }
  }
  .studio-eyebrow {
    color: #8d9db9;
    font-size: 9px;
    letter-spacing: 1.5px;
  }
  .studio-icon-button {
    border: 0;
    background: none;
    color: #8c99ad;
    width: 26px;
    height: 26px;
    display: inline-grid;
    place-items: center;
    border-radius: 4px;
    &:hover {
      color: var(--studio-accent);
      background: #eaf0f7;
    }
  }
  .studio-layout {
    display: grid;
    grid-template-columns: 222px minmax(0, 1fr) 266px;
    height: min(500px, calc(100vh - 230px));
    min-height: 240px;
  }
  .studio-fields {
    display: flex;
    flex-direction: column;
    min-height: 0;
    border-right: 1px solid #e3e8f0;
  }
  .studio-field-search {
    padding: 16px 12px 10px;
    flex-shrink: 0;
  }
  .studio-field-scroll {
    flex: 1;
    min-height: 0;
    overflow: auto;
    overscroll-behavior: contain;
    padding: 0 12px 14px;
  }
  .studio-field {
    display: flex;
    align-items: center;
    width: 100%;
    min-height: 40px;
    gap: 10px;
    text-align: left;
    background: none;
    border: 0;
    border-radius: 5px;
    padding: 9px 10px;
    color: #43536d;
    margin-bottom: 3px;
    &:hover,
    &.active {
      background: #edf1f7;
    }
    .studio-field-name {
      min-width: 0;
      flex: 1;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      font-size: 12px;
      small {
        display: block;
        font: 10px/18px Consolas, monospace;
        color: #9aa7bc;
        overflow: hidden;
        text-overflow: ellipsis;
      }
    }
    i {
      width: 5px;
      height: 5px;
      border-radius: 50%;
      background: #637d9e;
      flex-shrink: 0;
    }
  }
  .studio-type {
    color: #8fa0be;
    font-size: 11px;
    min-width: 18px;
    text-align: center;
    &.numeric {
      color: #6d9c90;
      font-size: 15px;
    }
  }
  .studio-editor {
    min-height: 0;
    min-width: 0;
    display: flex;
    flex-direction: column;
    padding: 20px 22px 0;
    overflow: hidden;
  }
  .studio-current {
    display: flex;
    align-items: center;
    gap: 9px;
    margin-bottom: 18px;
    min-height: 20px;
    strong {
      font-size: 13px;
      color: #35445d;
      white-space: nowrap;
    }
    code {
      font: 10px Consolas, monospace;
      color: #99a6b9;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    span {
      margin-left: auto;
      font-size: 10px;
      color: #9aa7bc;
      white-space: nowrap;
    }
  }
  .studio-tabs {
    display: flex;
    flex-shrink: 0;
    gap: 6px;
    padding-bottom: 12px;
    border-bottom: 1px solid #e2e8f0;
    button {
      background: none;
      border: 0;
      border-radius: 4px;
      color: #94a0b4;
      padding: 5px 9px;
      font-size: 12px;
      &.active {
        background: #edf1f7;
        color: var(--studio-accent);
      }
    }
  }
  .studio-value-tools {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 12px 0;
    flex-shrink: 0;
  }
  .studio-subtabs {
    display: flex;
    gap: 14px;
    button {
      background: none;
      border: 0;
      border-bottom: 1px solid transparent;
      padding: 3px 0 6px;
      color: #a0aabd;
      font-size: 11px;
      &.active {
        color: var(--studio-accent);
        border-color: var(--studio-accent);
      }
    }
  }
  .studio-segment {
    display: flex;
    padding: 2px;
    border: 1px solid #e3e8f0;
    border-radius: 4px;
    button {
      background: none;
      border: 0;
      border-radius: 3px;
      color: #98a5b9;
      padding: 3px 6px;
      font-size: 10px;
      &.active {
        background: #edf1f7;
        color: var(--studio-accent);
      }
    }
  }
  .studio-select-all {
    display: flex;
    align-items: center;
    justify-content: space-between;
    border-bottom: 1px solid #e3e8f0;
    flex-shrink: 0;
    min-height: 42px;
    > span {
      font-size: 10px;
      color: #9da9bb;
    }
    .ed-checkbox__label {
      font-size: 11px;
      color: #94a1b6;
    }
  }
  .studio-values-scroll {
    flex: 1;
    min-height: 0;
    overflow: auto;
    overscroll-behavior: contain;
    position: relative;
    padding: 5px 0;
    .ed-checkbox-group {
      display: flex;
      flex-direction: column;
      align-items: stretch;
    }
    .ed-checkbox {
      margin: 0;
      padding: 0 7px;
      min-height: 36px;
      flex-shrink: 0;
      border-radius: 4px;
      display: flex;
      align-items: center;
      &:hover {
        background: #f3f6fa;
      }
    }
    .ed-checkbox__label {
      font-size: 12px;
      color: #43536d;
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      > span {
        display: block;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
    }
  }
  .studio-selection-foot {
    display: flex;
    align-items: center;
    justify-content: space-between;
    flex-shrink: 0;
    min-height: 36px;
    border-top: 1px solid #e3e8f0;
    color: #9ca8ba;
    font-size: 10px;
    strong {
      color: var(--studio-accent);
      font-weight: 500;
    }
    button {
      background: none;
      border: 0;
      color: var(--studio-accent);
      font-size: 10px;
    }
  }
  .studio-paste {
    flex: 1;
    min-height: 0;
    overflow: auto;
    padding: 0 0 12px;
    p {
      font-size: 11px;
      color: #9aa6b9;
    }
    .ed-button {
      margin-top: 12px;
    }
    .ed-textarea__inner {
      font-size: 12px;
    }
  }
  .studio-condition {
    padding: 18px 0;
    overflow: auto;
    min-height: 0;
    label {
      display: block;
      font-size: 12px;
      color: #8392a9;
      margin-bottom: 8px;
    }
    .ed-select {
      width: 100%;
    }
    .studio-condition-input {
      margin-top: 18px;
    }
    .ed-date-editor {
      width: 100%;
    }
    p {
      font-size: 11px;
      line-height: 1.8;
      color: #9aa6b9;
      margin-top: 24px;
    }
  }
  .studio-summary {
    background: #fafbfd;
    display: flex;
    flex-direction: column;
    min-height: 0;
    border-left: 1px solid #e3e8f0;
    padding: 20px 17px;
  }
  .studio-logic {
    flex-shrink: 0;
    label {
      display: block;
      color: #9aa7ba;
      font-size: 10px;
      margin-bottom: 8px;
    }
    .ed-select {
      width: 100%;
    }
  }
  .studio-summary-title {
    display: flex;
    align-items: center;
    justify-content: space-between;
    flex-shrink: 0;
    padding: 19px 0 12px;
    font-size: 11px;
    color: #8291a8;
    small {
      background: #edf1f7;
      font-size: 9px;
      padding: 1px 4px;
      border-radius: 3px;
    }
    button {
      background: none;
      border: 0;
      color: var(--studio-accent);
      font-size: 11px;
    }
  }
  .studio-rules-scroll {
    min-height: 0;
    flex: 1;
    overflow: auto;
    overscroll-behavior: contain;
  }
  .studio-summary-empty {
    color: #a0abba;
    font-size: 11px;
    line-height: 1.9;
  }
  .studio-rule {
    border: 1px solid #e1e7ef;
    background: white;
    border-radius: 6px;
    padding: 9px 10px;
    margin-bottom: 10px;
    p {
      font-size: 10px;
      color: #99a6b9;
      margin: 4px 0 8px;
      overflow-wrap: anywhere;
    }
  }
  .studio-rule-title {
    display: flex;
    align-items: center;
    justify-content: space-between;
    > button:first-child {
      background: none;
      border: 0;
      padding: 0;
      font-size: 12px;
      color: #43536d;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      text-align: left;
    }
    .studio-icon-button {
      width: 20px;
      height: 20px;
      flex-shrink: 0;
    }
    .dataset-icon {
      width: 12px;
      height: 12px;
    }
  }
  .studio-rule-values {
    display: flex;
    flex-wrap: wrap;
    gap: 5px;
    max-height: 140px;
    overflow: auto;
    > span {
      max-width: 100%;
      display: flex;
      align-items: center;
      gap: 5px;
      background: #f0f3f8;
      border-radius: 3px;
      font-size: 10px;
      color: #6d7e98;
      padding: 4px 6px;
      overflow-wrap: anywhere;
    }
    button {
      border: 0;
      background: none;
      color: #9cabc0;
      padding: 0;
      font-size: 12px;
    }
  }
  .studio-estimate {
    flex-shrink: 0;
    padding-top: 15px;
    margin-top: 12px;
    border-top: 1px solid #e6ebf2;
    > span {
      color: #9da9b9;
      font-size: 10px;
    }
    div {
      display: flex;
      align-items: baseline;
      gap: 8px;
      margin-top: 5px;
    }
    strong {
      font-size: 26px;
      line-height: 34px;
      font-weight: 600;
      color: var(--studio-accent);
    }
    small {
      color: #a0abba;
      font-size: 10px;
    }
  }
  .studio-footer {
    display: flex;
    justify-content: flex-end;
    align-items: center;
    gap: 20px;
    padding: 14px 24px;
    border-top: 1px solid #e3e8f0;
    > span {
      display: flex;
      align-items: center;
      gap: 6px;
      color: #94a2b7;
      font-size: 10px;
    }
    .dataset-icon {
      width: 13px;
      height: 13px;
    }
    .ed-button--primary {
      min-width: 115px;
    }
  }
  .studio-empty {
    font-size: 12px;
    line-height: 1.9;
    color: #9aa6b9;
    padding: 20px 12px;
    text-align: center;
  }
  .studio-candidate-error {
    padding: 20px 8px;
    color: #ad7945;
    font-size: 12px;
    text-align: center;
  }
  .studio-load-more {
    width: 100%;
    font-size: 11px;
    color: var(--studio-accent);
  }
}
@media (max-width: 1100px) {
  .dataset-filter-studio.ed-dialog .studio-layout {
    grid-template-columns: 190px minmax(0, 1fr) 230px;
  }
  .dataset-filter-studio.ed-dialog .studio-editor {
    padding-left: 16px;
    padding-right: 16px;
  }
}
@media (max-height: 700px) {
  .dataset-filter-studio.ed-dialog .studio-layout {
    height: calc(100vh - 205px);
    min-height: 200px;
  }
  .dataset-filter-studio.ed-dialog .studio-header {
    padding: 14px 22px 12px;
  }
  .dataset-filter-studio.ed-dialog .studio-footer {
    padding: 12px 22px;
  }
}
</style>

<style lang="less">
.dataset-centered-overlay .ed-overlay-dialog {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
}
.dataset-centered-overlay .ed-dialog {
  margin: 0 !important;
  max-height: calc(100dvh - 40px);
  border-radius: 14px;
  overflow: hidden;
}
.dataset-filter-studio.ed-dialog {
  .studio-layout {
    grid-template-columns: 220px minmax(0, 1fr);
    height: min(540px, calc(100dvh - 180px));
    min-height: 0;
  }
  .studio-editor {
    padding: 16px 22px;
  }
  .studio-header {
    border: 0;
    padding: 20px 24px 14px;
  }
  .studio-fields {
    border: 0;
    background: #f6f8fb;
    border-radius: 10px;
    margin-left: 12px;
  }
  .studio-footer {
    border: 0;
    padding: 16px 24px;
  }
  .studio-rule-toolbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    margin-bottom: 14px;
    flex-shrink: 0;
  }
  .studio-logic {
    margin: 0;
    width: 170px;
    label {
      display: none;
    }
  }
  .studio-inline-count {
    font-size: 12px;
    color: #8190a6;
  }
  .studio-rule-details {
    flex-shrink: 0;
    padding: 8px 12px;
    margin-bottom: 12px;
    background: #f4f6fa;
    border-radius: 8px;
    summary {
      cursor: pointer;
      font-size: 12px;
      color: #5b70a8;
    }
  }
  .studio-rules-scroll {
    max-height: 110px;
    overflow: auto;
  }
  .studio-rule {
    padding: 8px 0;
  }
  .studio-current {
    margin-bottom: 12px;
  }
  .studio-tabs {
    padding-bottom: 10px;
  }
}
</style>
