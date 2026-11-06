<script lang="ts" setup>
import { ElMessage } from 'element-plus-secondary'
import { inject, computed, ref, nextTick, provide } from 'vue'
import RowAuth from '@/views/chart/components/editor/filter/auth-tree-chart/RowAuth.vue'
import { snapshotStoreWithOut } from '@/store/modules/data-visualization/snapshot'
import { cloneDeep } from 'lodash-es'

const emits = defineEmits(['filter-data'])
const filedList = inject('filedList', () => [])
const rowAuth = ref()
const dialogVisible = ref(false)
const computedFiledList = computed(() => {
  return filedList().reduce((pre, next) => {
    if (next.id !== '-1') {
      pre[next.id] = next
    }
    return pre
  }, {})
})

provide('filedList', computedFiledList)

const closeFilter = () => {
  dialogVisible.value = false
}
const snapshotStore = snapshotStoreWithOut()

const submit = () => {
  rowAuth.value.submit()
}

const changeFilter = val => {
  const { logic, items, errorMessage } = val
  if (errorMessage) {
    ElMessage.error({
      message: errorMessage,
      type: 'error',
      showClose: true
    })
    return
  }
  dfsTreeDelete(items)
  emits('filter-data', { logic, items })
  snapshotStore.recordSnapshotCache('changeFilter')
  dialogVisible.value = false
}

const dfsTreeDelete = arr => {
  arr.forEach(ele => {
    if (ele?.subTree?.items?.length) {
      dfsTreeDelete(ele.subTree.items || [])
    } else {
      if (ele.field) {
        delete ele.field
      }
    }
  })
}

const dfsTree = arr => {
  arr.forEach(ele => {
    if (ele?.subTree?.items?.length) {
      dfsTree(ele.subTree.items)
    } else {
      if (computedFiledList.value[ele.fieldId]) {
        ele.field = computedFiledList.value[ele.fieldId]
      }
    }
  })
}

const init = tree => {
  dialogVisible.value = true
  nextTick(() => {
    const draft = cloneDeep(tree || {})
    dfsTree(draft.items || [])
    rowAuth.value.init(draft)
  })
}

defineExpose({
  init
})
</script>
<template>
  <el-dialog
    width="min(1080px, calc(100vw - 32px))"
    append-to-body
    :title="$t('chart.add_filter')"
    destroy-on-close
    :close-on-click-modal="false"
    class="de-dialog-form filter-tree-cont"
    v-model="dialogVisible"
  >
    <div class="filter-description">
      组合筛选条件，选择需要展示的数据。字段和值都可直接输入搜索。
    </div>
    <div class="tree-cont">
      <div class="content">
        <RowAuth @save="changeFilter" ref="rowAuth" />
      </div>
    </div>
    <template #footer>
      <el-button secondary @click="closeFilter">{{ $t('chart.cancel') }} </el-button>
      <el-button type="primary" @click="submit">{{ $t('chart.confirm') }} </el-button>
    </template>
  </el-dialog>
</template>
<style lang="less">
.filter-tree-cont {
  max-height: calc(100dvh - 48px);
  height: auto !important;
  margin-top: 24px !important;
  margin-bottom: 24px !important;
  display: flex;
  flex-direction: column;

  .ed-dialog__header,
  .ed-dialog__footer {
    flex-shrink: 0;
  }

  .ed-dialog__body {
    min-height: 0;
    overflow: auto;
    padding-top: 12px;
    padding-bottom: 16px;
  }

  .filter-description {
    color: #64748b;
    font-size: 13px;
    margin-bottom: 16px;
  }
  .ed-dialog__footer {
    border-top: 1px solid #e8ecf1;
    padding-top: 16px;
  }

  .tree-cont {
    min-height: 67px;
    width: 100%;
    padding: 0;
    .content {
      height: auto;
      width: 100%;
    }
  }
}
</style>
