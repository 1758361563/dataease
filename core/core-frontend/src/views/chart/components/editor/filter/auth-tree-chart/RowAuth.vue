<script lang="ts" setup>
import { ref } from 'vue'
import { useI18n } from '@/hooks/web/useI18n'
import AuthTree from './AuthTree.vue'

const { t } = useI18n()

const errorMessage = ref('')
const logic = ref<'or' | 'and'>('and')
const relationList = ref([])

const init = expressionTree => {
  const { items } = expressionTree
  logic.value = (items?.length ? expressionTree.logic : undefined) || 'and'
  relationList.value = dfsInit(items || [])
}
const submit = () => {
  errorMessage.value = ''
  emits('save', {
    logic: logic.value,
    items: dfsSubmit(relationList.value),
    errorMessage: errorMessage.value
  })
}
const errorDetected = ({
  enumValue,
  deType,
  filterType,
  term,
  value,
  name,
  timeValue,
  filterTypeTime
}) => {
  if (!name) {
    errorMessage.value = t('data_set.cannot_be_empty_')
    return
  }
  if (filterType === 'logic') {
    if (!term) {
      errorMessage.value = t('data_set.cannot_be_empty_de_ruler')
      return
    }
    if (
      !term.includes('null') &&
      !term.includes('empty') &&
      ['', null, undefined].includes(value) &&
      deType !== 1
    ) {
      errorMessage.value = t('chart.filter_value_can_null')
      return
    }

    if (
      !term.includes('null') &&
      !term.includes('empty') &&
      ['', null, undefined].includes(timeValue) &&
      deType === 1 &&
      filterTypeTime === 'dynamicDate'
    ) {
      errorMessage.value = t('chart.filter_value_can_null')
      return
    }
    if ([2, 3].includes(deType)) {
      if (parseFloat(value).toString() === 'NaN') {
        errorMessage.value = t('chart.filter_value_can_not_str')
        return
      }
    }
  }
  if (filterType === 'enum') {
    if (!['null', 'not_null'].includes(term) && (enumValue || []).length < 1) {
      errorMessage.value = t('chart.enum_value_can_not_null')
      return
    }
  }
}

const getTimeValue = dynamicTimeSetting => {
  const relativeToCurrentTypeMap = {
    year: '年',
    month: '月',
    date: '日',
    datetime: '日'
  }
  let timeValue = ''
  const {
    timeGranularity,
    timeNum,
    relativeToCurrentType,
    around,
    arbitraryTime,
    relativeToCurrent
  } = dynamicTimeSetting || {}
  if (relativeToCurrent !== 'custom') {
    timeValue = [
      {
        label: '今年',
        value: 'thisYear'
      },
      {
        label: '去年',
        value: 'lastYear'
      },
      {
        label: '本月',
        value: 'thisMonth'
      },
      {
        label: '上月',
        value: 'lastMonth'
      },
      {
        label: '今天',
        value: 'today'
      },
      {
        label: '昨天',
        value: 'yesterday'
      },
      {
        label: '月初',
        value: 'monthBeginning'
      },
      {
        label: '年初',
        value: 'yearBeginning'
      }
    ].find(ele => ele.value === relativeToCurrent)?.label
    return timeValue
  }
  timeValue = `${timeNum}${relativeToCurrentTypeMap[relativeToCurrentType]}${
    around === 'f' ? '前' : '后'
  }`
  if (timeGranularity === 'datetime') {
    timeValue += new Date(arbitraryTime).toLocaleString().split(' ')[1]
  }

  return timeValue
}
const dfsInit = arr => {
  const elementList = []
  arr.forEach(ele => {
    const { subTree } = ele
    if (subTree) {
      const { items = [], logic } = subTree
      const child = dfsInit(items)
      elementList.push({ logic: logic || 'and', child })
    } else {
      const {
        enumValue,
        filterTypeTime,
        dynamicTimeSetting,
        fieldId,
        filterType,
        term,
        timeType,
        value,
        field
      } = ele
      const { name, deType } = field || {}
      elementList.push({
        enumValue,
        fieldId,
        filterType,
        term,
        timeType,
        timeValue: getTimeValue(dynamicTimeSetting),
        filterTypeTime,
        dynamicTimeSetting,
        value,
        name,
        deType
      })
    }
  })
  return elementList
}
const dfsSubmit = arr => {
  const items = []
  arr.forEach(ele => {
    const { child = [] } = ele
    if (child.length) {
      const { logic } = ele
      const subTree = dfsSubmit(child)
      items.push({
        enumValue: [],
        fieldId: '',
        filterType: '',
        term: '',
        timeType: 'year',
        type: 'tree',
        value: '',
        filterTypeTime: 'dateValue',
        timeValue: '',
        dynamicTimeSetting: {},
        subTree: { logic, items: subTree }
      })
    } else {
      const {
        enumValue,
        filterTypeTime,
        dynamicTimeSetting,
        fieldId,
        filterType,
        deType,
        term,
        timeType,
        value,
        name,
        timeValue
      } = ele
      errorDetected({ deType, enumValue, filterType, term, value, name, timeValue, filterTypeTime })
      if (fieldId) {
        items.push({
          enumValue: enumValue || [],
          fieldId,
          timeValue,
          filterType,
          filterTypeTime,
          dynamicTimeSetting,
          term,
          timeType,
          value,
          type: 'item',
          subTree: null
        })
      }
    }
  })
  return items
}
const removeRelationList = () => {
  relationList.value = []
}

const addCondReal = (type, logic) => {
  relationList.value.push(
    type === 'condition'
      ? {
          fieldId: '',
          value: '',
          enumValue: [],
          term: '',
          timeType: 'year',
          filterType: 'logic',
          name: '',
          timeValue: '',
          filterTypeTime: 'dateValue',
          dynamicTimeSetting: {},
          deType: ''
        }
      : { child: [], logic: logic || 'and' }
  )
}
const del = index => {
  relationList.value.splice(index, 1)
}

defineExpose({
  init,
  submit
})
const emits = defineEmits(['save'])
</script>

<template>
  <div class="rowAuth">
    <auth-tree
      @del="idx => del(idx)"
      @addCondReal="addCondReal"
      @removeRelationList="removeRelationList"
      :relationList="relationList"
      v-model:logic="logic"
    />
  </div>
</template>

<style lang="less" scoped>
.rowAuth {
  font-family: inherit;
  text-align: left;
  color: #2c3e50;
  position: relative;
}
.real-line,
.dash-line {
  display: none;
}
</style>
