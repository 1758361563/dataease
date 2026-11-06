<script lang="ts" setup>
import icon_deleteTrash_outlined from '@/assets/svg/icon_delete-trash_outlined.svg'
import { ref, inject, computed, watch, onBeforeMount, toRefs, nextTick } from 'vue'
import { useI18n } from '@/hooks/web/useI18n'
import type { SelectConfig } from '../TimeDialog.vue'
import TimeDialog from '@/views/chart/components/editor/filter/TimeDialog.vue'
import { multFieldValuesForPermissions } from '@/api/dataset'
import {
  textOptions,
  dateOptions,
  valueOptions,
  textOptionsForSysParams,
  sysParamsIlns
} from '@/views/visualized/data/dataset/options.js'
import TimeSetDialog from '@/components/time-set-dialog/index.vue'
export interface Item {
  term: string
  fieldId: string
  filterType: string
  deType: number
  enumValue: string[]
  name: string
  value: number
  filterTypeTime?: string
  timeValue: string
  dynamicTimeSetting?: SelectConfig
  timeType?: string
}

type Props = {
  index: number
  item: Item
}

const props = withDefaults(defineProps<Props>(), {
  index: 0,
  item: () => ({
    term: '',
    fieldId: '',
    filterType: '',
    deType: 0,
    enumValue: [],
    name: '',
    filterTypeTime: 'dateValue',
    value: null,
    dynamicTimeSetting: null,
    timeValue: ''
  })
})

const { t } = useI18n()

const enumList = ref([])
const showTextArea = ref(false)

const textareaValue = ref('')

const { item } = toRefs(props)

const getAuthTargetType = inject('getAuthTargetType')
const filedList = inject('filedList')

const sysParamsIln = computed(() => {
  if (['in', 'not in'].includes(item.value.term)) {
    return [
      {
        value: '${sysParams.roles}',
        label: t('auth.sysParams_type.role')
      }
    ]
  } else {
    return sysParamsIlns
  }
})
const operators = computed(() => {
  const { deType } = item.value
  if (authTargetType.value === 'sysParams') {
    return textOptionsForSysParams
  }
  if ([0, 5, 7].includes(deType)) {
    return textOptions
  } else if (deType === 1) {
    return dateOptions
  } else {
    return valueOptions
  }
})
const enumOperators = computed(() =>
  ['eq', 'not_eq', 'in', 'not in', 'null', 'not_null'].map(value => ({
    value,
    label: `chart.filter_${value.replace(' ', '_')}`
  }))
)
const conditionOperators = computed(() => {
  if (![0, 5, 7].includes(item.value.deType)) return operators.value
  const options = [...enumOperators.value]
  textOptions.forEach(option => {
    if (!options.some(({ value }) => value === option.value)) options.push(option)
  })
  return options
})
const enumTerm = computed(() => item.value.filterType === 'enum')
const termNeedsValue = computed(
  () => !['null', 'not_null', 'empty', 'not_empty'].includes(item.value.term)
)

const computedFiledList = computed(() => {
  return Object.values(filedList.value || {})
})

const authTargetType = ref('')

watch(
  () => getAuthTargetType?.authTargetType,
  value => {
    if (authTargetType.value === value || !value) return
    authTargetType.value = value
  },
  {
    immediate: true
  }
)

onBeforeMount(() => {
  initNameEnumName()
})

const filterTypeChangeTime = () => {
  item.value.term = ''
  item.value.timeValue = null
}

const dialogVisible = ref(false)
const timeDialog = ref()
const handleClick = () => {
  dialogVisible.value = true
  nextTick(() => {
    timeDialog.value.init(item.value.dynamicTimeSetting || {})
  })
}

const relativeToCurrentTypeMap = {
  year: t('cron.year'),
  month: t('cron.month'),
  date: t('cron.day'),
  datetime: t('cron.day')
}
function formatDate(str) {
  // 根据 - 符号拆分
  return str
    .split('\/')
    .map(item => {
      // +item 将item字符串转换为数字
      // 小于10的时候就补全一个前缀0
      if (+item < 10) {
        return '0' + +item
      }
      // 大于10的时候不用补0
      return item
    })
    .join('/') // 最后重组回来
}

const confirmTimeSelect = () => {
  item.value.dynamicTimeSetting = { ...timeDialog.value.curComponent }
  const {
    timeGranularity,
    timeNum,
    relativeToCurrentType,
    around,
    arbitraryTime,
    relativeToCurrent
  } = item.value.dynamicTimeSetting
  if (arbitraryTime) {
    item.value.dynamicTimeSetting.arbitraryTime =
      formatDate(new Date(arbitraryTime).toLocaleDateString()) +
      ' ' +
      new Date(arbitraryTime).toLocaleTimeString()
  }
  if (relativeToCurrent !== 'custom') {
    item.value.timeValue = [
      {
        label: t('dynamic_year.current'),
        value: 'thisYear'
      },
      {
        label: t('dynamic_year.last'),
        value: 'lastYear'
      },
      {
        label: t('cron.this_month'),
        value: 'thisMonth'
      },
      {
        label: t('dynamic_month.last'),
        value: 'lastMonth'
      },
      {
        label: t('dynamic_time.today'),
        value: 'today'
      },
      {
        label: t('dynamic_time.yesterday'),
        value: 'yesterday'
      },
      {
        label: t('dynamic_time.firstOfMonth'),
        value: 'monthBeginning'
      },
      {
        label: t('dynamic_time.firstOfYear'),
        value: 'yearBeginning'
      }
    ].find(ele => ele.value === relativeToCurrent).label
    dialogVisible.value = false
    return
  }
  item.value.timeValue = `${timeNum}${relativeToCurrentTypeMap[relativeToCurrentType]}${
    around === 'f' ? t('dynamic_time.before') : t('dynamic_time.after')
  }`
  if (timeGranularity === 'datetime') {
    item.value.timeValue += new Date(arbitraryTime).toLocaleString().split(' ')[1]
  }

  dialogVisible.value = false
}

const optionData = data => {
  if (!data) return []
  return data.filter(item => item !== null && item !== undefined)
}

const filterListTime = [
  {
    value: 'dateValue',
    label: t('auth.fixed_value')
  },
  {
    value: 'dynamicDate',
    label: t('chart.dynamic')
  }
]

const timeDialogRef = ref()
const showTimeDialog = (obj: any) => {
  if (obj.deType !== 1) return
  timeDialogRef.value.init(obj.timeType, obj.value)
}

const saveTime = (type, value) => {
  item.value.timeType = type
  item.value.value = value
}

const emits = defineEmits(['update:item', 'del'])

const enumLoading = ref(false)
const enumLoadError = ref(false)
let enumRequest = 0
let enumRequestField = ''
const filterPopperOptions = {
  strategy: 'fixed',
  modifiers: [
    { name: 'flip', options: { fallbackPlacements: ['bottom-start', 'top-start'], padding: 12 } },
    { name: 'preventOverflow', options: { boundary: 'viewport', padding: 12 } }
  ]
}
const enumQuery = ref('')
const enumOptions = computed(() => {
  const values = [...new Set([...(item.value.enumValue || []), ...enumList.value])]
  const query = enumQuery.value.trim().toLowerCase()
  return query ? values.filter(value => String(value).toLowerCase().includes(query)) : values
})
const selectAllFiltered = () => {
  item.value.enumValue = [...new Set([...item.value.enumValue, ...enumOptions.value])]
}
const initEnumOptions = async () => {
  const { deType, filterType, fieldId } = item.value
  if (
    authTargetType.value === 'sysParams' ||
    filterType !== 'enum' ||
    ![0, 5, 7].includes(deType) ||
    !fieldId
  )
    return
  if (enumLoading.value && enumRequestField === String(fieldId)) return
  const request = ++enumRequest
  enumRequestField = String(fieldId)
  enumLoading.value = true
  enumLoadError.value = false
  try {
    const response = await multFieldValuesForPermissions({ fieldIds: [fieldId] })
    if (request === enumRequest && String(item.value.fieldId) === String(fieldId))
      enumList.value = optionData(response.data)
  } catch {
    if (request === enumRequest && String(item.value.fieldId) === String(fieldId))
      enumLoadError.value = true
  } finally {
    if (request === enumRequest) enumLoading.value = false
  }
}
const initNameEnumName = () => {
  item.value.enumValue ||= []
  if (item.value.filterType === 'enum' && !item.value.term) item.value.term = 'in'
  initEnumOptions()
}
const openEnumPicker = (visible: boolean) => {
  if (!visible) enumQuery.value = ''
  if (visible && !enumList.value.length) initEnumOptions()
}
const selectItem = ({ name, id, deType }) => {
  ++enumRequest
  enumQuery.value = ''
  enumLoading.value = false
  enumList.value = []
  Object.assign(item.value, {
    fieldId: id,
    name,
    deType,
    filterType: [0, 5, 7].includes(deType) ? 'enum' : 'logic',
    enumValue: [],
    value: '',
    term: [0, 5, 7].includes(deType) ? 'in' : 'eq',
    filterTypeTime: 'dateValue',
    timeValue: '',
    dynamicTimeSetting: {}
  })
  initEnumOptions()
}
const selectField = id => {
  const field = computedFiledList.value.find(field => String(field.id) === String(id))
  if (field) selectItem(field)
  else selectItem({ id: '', name: '', deType: 0 })
}
const enumSelectionChange = values => {
  if (['eq', 'not_eq'].includes(item.value.term) && values.length > 1)
    item.value.enumValue = values.slice(-1)
}
const operatorChange = term => {
  if (['in', 'not in'].includes(term)) {
    item.value.filterType = 'enum'
    item.value.value = ''
    initEnumOptions()
  } else if (['like', 'not like', 'empty', 'not_empty'].includes(term)) {
    item.value.filterType = 'logic'
    item.value.enumValue = []
  }
  if (['eq', 'not_eq'].includes(term) && item.value.enumValue.length > 1)
    item.value.enumValue = item.value.enumValue.slice(0, 1)
  if (!termNeedsValue.value) {
    item.value.value = ''
    item.value.enumValue = []
  }
}
const addFields = () => {
  const values = textareaValue.value
    .split('\n')
    .map(value => value.trim())
    .filter(Boolean)
  item.value.enumValue = [...new Set([...item.value.enumValue, ...values])]
  textareaValue.value = ''
  showTextArea.value = false
}
</script>

<template>
  <div class="white-nowrap">
    <div class="filed condition-row">
      <el-select
        class="condition-field"
        v-model="item.fieldId"
        filterable
        clearable
        placeholder="选择或搜索字段"
        placement="bottom-start"
        popper-class="chart-filter-options"
        :popper-options="filterPopperOptions"
        @change="selectField"
      >
        <el-option
          v-for="field in computedFiledList"
          :key="field.id"
          :label="field.name"
          :value="field.id"
          :title="field.name"
        />
      </el-select>
      <div class="condition-controls" v-if="item.fieldId">
        <el-select
          class="condition-operator"
          size="small"
          v-model="item.term"
          @change="operatorChange"
          :placeholder="t('auth.default_method')"
        >
          <el-option
            v-for="ele in conditionOperators"
            :key="ele.value"
            :label="t(ele.label)"
            :value="ele.value"
          />
        </el-select>

        <template v-if="item.deType === 1 && termNeedsValue">
          <el-select
            size="small"
            class="date-value-type"
            @change="filterTypeChangeTime"
            v-model="item.filterTypeTime"
            :placeholder="t('auth.select')"
          >
            <el-option
              v-for="ele in filterListTime"
              :key="ele.value"
              :label="ele.label"
              :value="ele.value"
            >
            </el-option>
          </el-select>
        </template>

        <template v-if="!enumTerm && termNeedsValue">
          <template v-if="authTargetType === 'sysParams'">
            <el-select class="condition-value" size="small" v-model="item.value">
              <el-option
                v-for="itx in sysParamsIln"
                :key="itx.value"
                :label="t(itx.label)"
                :value="itx.value"
              >
              </el-option>
            </el-select>
          </template>
          <template v-else-if="[2, 3].includes(item.deType) && termNeedsValue">
            <el-input-number
              class="condition-value"
              size="small"
              effect="plain"
              v-model="item.value"
              controls-position="right"
            ></el-input-number>
          </template>
          <template v-else>
            <el-input
              v-if="item.deType === 1 && item.filterTypeTime === 'dynamicDate' && !item.timeValue"
              @click="handleClick"
              readonly
              class="condition-value"
              size="small"
              v-model="item.timeValue"
            />
            <el-tooltip
              class="item"
              v-else-if="item.deType === 1 && item.filterTypeTime === 'dynamicDate'"
              effect="light"
              :content="item.timeValue"
              placement="top"
              ><el-input
                @click="handleClick"
                readonly
                class="condition-value"
                size="small"
                v-model="item.timeValue"
            /></el-tooltip>
            <el-tooltip
              class="item"
              v-else-if="item.deType === 1 && item.filterTypeTime !== 'dynamicDate'"
              effect="light"
              :content="item.value"
              placement="top"
              ><el-input
                readonly
                @click="showTimeDialog(item)"
                class="condition-value"
                size="small"
                v-model="item.value"
            /></el-tooltip>
            <el-input v-else class="condition-value" size="small" v-model="item.value" />
          </template>
        </template>

        <div v-if="enumTerm && termNeedsValue" class="condition-enum">
          <el-select
            v-model="item.enumValue"
            class="condition-value"
            filterable
            :filter-method="query => (enumQuery = query)"
            multiple
            allow-create
            default-first-option
            :reserve-keyword="false"
            collapse-tags
            collapse-tags-tooltip
            :multiple-limit="['eq', 'not_eq'].includes(item.term) ? 1 : 0"
            :loading="enumLoading"
            placeholder="输入搜索或回车添加值"
            placement="bottom-start"
            popper-class="chart-filter-options"
            :popper-options="filterPopperOptions"
            @visible-change="openEnumPicker"
            @change="enumSelectionChange"
          >
            <template #header>
              <div v-if="!['eq', 'not_eq'].includes(item.term)" class="chart-filter-enum-actions">
                <el-button link @mousedown.prevent @click="selectAllFiltered"
                  >全选搜索结果</el-button
                >
                <el-button link @mousedown.prevent @click="item.enumValue = []">清空</el-button>
              </div>
            </template>
            <el-option
              v-for="value in enumOptions"
              :key="String(value)"
              :label="String(value)"
              :value="value"
              :title="String(value)"
            />
            <template #empty
              ><div class="chart-filter-empty">
                {{
                  enumLoadError
                    ? '加载失败，可直接输入值后回车添加'
                    : '暂无匹配值，可直接输入后回车添加'
                }}
              </div></template
            >
          </el-select>
          <el-button v-if="!['eq', 'not_eq'].includes(item.term)" link @click="showTextArea = true"
            >批量输入</el-button
          >
        </div>
      </div>
      <el-icon class="font12 condition-delete" @click="emits('del')">
        <Icon name="icon_delete-trash_outlined"
          ><icon_deleteTrash_outlined class="svg-icon"
        /></Icon>
      </el-icon>
    </div>
    <el-dialog
      v-model="showTextArea"
      title="批量输入筛选值"
      width="min(520px, calc(100vw - 32px))"
      append-to-body
      destroy-on-close
    >
      <el-input
        v-model="textareaValue"
        type="textarea"
        :rows="8"
        placeholder="每行一个值，重复值会自动合并"
      />
      <template #footer>
        <el-button @click="showTextArea = false">取消</el-button>
        <el-button type="primary" @click="addFields">添加</el-button>
      </template>
    </el-dialog>
    <el-dialog
      class="create-dialog"
      append-to-body
      v-model="dialogVisible"
      :title="t('common.date_setting')"
      width="420"
    >
      <TimeDialog ref="timeDialog"></TimeDialog>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="dialogVisible = false">{{ t('dataset.cancel') }}</el-button>
          <el-button type="primary" @click="confirmTimeSelect">
            {{ t('dataset.confirm') }}
          </el-button>
        </div>
      </template>
    </el-dialog>
  </div>
  <TimeSetDialog @saveTime="saveTime" ref="timeDialogRef"></TimeSetDialog>
</template>

<style lang="less" scoped>
.white-nowrap {
  min-width: 0;
}
.condition-row {
  display: grid;
  grid-template-columns: minmax(160px, 1fr) minmax(0, 2fr) 24px;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #fff;
  width: 100%;
  box-sizing: border-box;
  .condition-field {
    min-width: 0;
    width: 100%;
  }
  .condition-controls {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
    min-width: 0;
  }
  .condition-operator {
    flex: 0 0 130px;
    width: 130px;
  }
  .condition-value {
    flex: 1 1 140px;
    min-width: 120px;
    width: 100%;
  }
  .date-value-type {
    width: 110px;
    flex: 0 0 110px;
  }
  .condition-enum {
    display: flex;
    flex: 1;
    align-items: center;
    gap: 8px;
    min-width: 0;
  }
  .condition-delete {
    cursor: pointer;
    color: #64748b;
    justify-self: center;
  }
  .condition-delete:hover {
    color: #dc2626;
  }
  :deep(.ed-select__wrapper),
  :deep(.ed-input__wrapper) {
    min-height: 36px;
    font-size: 14px;
    border-radius: 6px;
  }
}
@media (max-width: 720px) {
  .condition-row {
    grid-template-columns: minmax(0, 1fr) 24px;
    gap: 8px;
  }
  .condition-controls {
    grid-column: 1;
    grid-row: 2;
  }
  .condition-delete {
    grid-column: 2;
    grid-row: 1;
  }
}
</style>
<style lang="less">
.chart-filter-options {
  max-width: calc(100vw - 32px);
  .ed-select-dropdown__wrap {
    max-height: min(240px, 40dvh);
  }
  .ed-select-dropdown__item {
    max-width: min(440px, calc(100vw - 48px));
  }
}
.chart-filter-empty {
  padding: 14px;
  color: #64748b;
  font-size: 13px;
}
.chart-filter-enum-actions {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}
</style>
