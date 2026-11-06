<script lang="ts">
import icon_deleteTrash_outlined from '@/assets/svg/icon_delete-trash_outlined.svg'
export default {
  name: 'logic-relation'
}
</script>
<script lang="ts" setup>
import { useI18n } from '@/hooks/web/useI18n'
import { PropType, toRefs } from 'vue'
import FilterFiled from './FilterFiled.vue'
import type { Item } from './FilterFiled.vue'
export type Logic = 'or' | 'and'
export type Relation = {
  child?: Relation[]
  logic: Logic
  x: number
} & Item
const { t } = useI18n()

const props = defineProps({
  relationList: {
    type: Array as PropType<Relation[]>,
    default: () => []
  },
  x: {
    type: Number,
    default: 0
  },
  logic: {
    type: String as PropType<Logic>,
    default: 'and'
  }
})

const emits = defineEmits([
  'addCondReal',
  'changeAndOrDfs',
  'update:logic',
  'removeRelationList',
  'del'
])

const { relationList } = toRefs(props)

const handleCommand = type => {
  emits('update:logic', type)
}

const removeRelationList = index => {
  relationList.value.splice(index, 1)
}
const addCondReal = type => {
  emits('addCondReal', type, 'and')
}
const add = (type, child, logic) => {
  child.push(
    type === 'condition'
      ? {
          fieldId: '',
          value: '',
          enumValue: [],
          term: '',
          filterType: 'logic',
          name: '',
          filterTypeTime: 'dateValue',
          timeValue: '',
          timeType: 'year',
          dynamicTimeSetting: {},
          deType: ''
        }
      : { child: [], logic: logic || 'and' }
  )
}
const del = (index, child) => {
  child.splice(index, 1)
}
</script>

<template>
  <div class="logic">
    <div class="logic-toolbar">
      <el-select
        class="logic-select"
        size="small"
        :model-value="logic"
        @change="handleCommand"
        aria-label="条件关系"
      >
        <el-option value="and" label="AND · 全部满足" />
        <el-option value="or" label="OR · 任一满足" />
      </el-select>
      <span class="logic-caption">{{
        logic === 'and' ? '同时满足以下条件' : '满足以下任一条件'
      }}</span>
      <el-button v-if="x" class="remove-group" link @click="emits('removeRelationList')">
        <el-icon><icon_deleteTrash_outlined /></el-icon>删除组
      </el-button>
    </div>
    <div class="logic-right">
      <template v-for="(item, index) in relationList" :key="index">
        <logic-relation
          v-if="item.child"
          :x="x + 1"
          :logic="item.logic || 'and'"
          @update:logic="value => (item.logic = value)"
          :relationList="item.child"
          @del="idx => del(idx, item.child)"
          @addCondReal="(type, logic) => add(type, item.child, logic)"
          @removeRelationList="removeRelationList(index)"
        />
        <filter-filed v-else :item="item" @del="emits('del', index)" :index="index" />
      </template>
      <div class="logic-right-add">
        <el-button link @click="addCondReal('condition')"
          >+ {{ t('auth.add_condition') }}</el-button
        >
        <el-button v-if="x < 2" link @click="addCondReal('relation')"
          >+ {{ t('auth.add_relationship') }}</el-button
        >
      </div>
    </div>
  </div>
</template>
<style lang="less" scoped>
.logic {
  padding: 14px;
  border: 1px solid #e2e8f0;
  background: #f8fafc;
  border-radius: 10px;
  width: 100%;
  box-sizing: border-box;
}
.logic-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}
.logic-select {
  width: 168px;
  flex: 0 0 168px;
}
.logic-caption {
  color: #64748b;
  font-size: 12px;
}
.remove-group {
  margin-left: auto;
  color: #64748b;
}
.logic-right {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
}
.logic-right-add {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  padding: 4px 0;
}
@media (max-width: 720px) {
  .logic {
    padding: 10px;
  }
  .logic-caption {
    display: none;
  }
}
</style>
