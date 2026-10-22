<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import dayjs from 'dayjs'
import { findDashboardShortcutsApi, storeApi } from '@/api/visualization/dataVisualization'
import {
  dashboardShortcuts,
  type DashboardShortcutMode,
  type DashboardShortcut
} from '@/utils/dashboardQuickAccess'
import type { BusiTreeNode } from '@/models/tree/TreeNode'
import { useEmitt } from '@/hooks/web/useEmitt'
import { Icon } from '@/components/icon-custom'
import dashboardIcon from '@/assets/svg/scent/layout-dashboard.svg'
import favoriteIcon from '@/assets/svg/visual-star.svg'
import emptyFavoriteIcon from '@/assets/svg/icon_collection_outlined.svg'

const props = defineProps<{
  mode: DashboardShortcutMode
  keyword?: string
  catalog: BusiTreeNode[]
  ready: boolean
  selectedId?: string | number
}>()
const emit = defineEmits<{
  (event: 'select', row: DashboardShortcut): void
  (event: 'ready', rows: DashboardShortcut[]): void
}>()
const records = ref<Record<string, any>[]>([])
const loading = ref(false)
const failed = ref(false)
const loaded = ref(false)
const saving = ref(new Set<string>())
const rows = computed(() => dashboardShortcuts(records.value, props.catalog, props.mode))
let revision = 0
let timer: ReturnType<typeof setTimeout>
let alive = true
const load = async () => {
  if (!props.ready) return
  const requestId = ++revision
  loading.value = true
  failed.value = false
  loaded.value = false
  try {
    const result = await findDashboardShortcutsApi(props.mode, props.keyword?.trim())
    if (requestId !== revision || !alive) return
    records.value = Array.isArray(result.data) ? result.data : []
    loaded.value = true
  } catch {
    if (requestId === revision && alive) {
      failed.value = true
      records.value = []
    }
  } finally {
    if (requestId === revision && alive) loading.value = false
  }
}
watch(
  () => [props.mode, props.keyword, props.ready],
  () => {
    clearTimeout(timer)
    ++revision
    records.value = []
    loaded.value = false
    failed.value = false
    if (props.ready) {
      loading.value = true
      timer = setTimeout(load, props.keyword?.trim() ? 200 : 0)
    }
  },
  { immediate: true }
)
watch([rows, loaded, () => props.ready], () => {
  if (loaded.value && props.ready && !failed.value) emit('ready', rows.value)
})
useEmitt({ name: 'dashboard-favorite-change', callback: load })
useEmitt({
  name: 'dashboard-opened',
  callback: () => {
    if (props.mode === 'recent') load()
  }
})
onBeforeUnmount(() => {
  alive = false
  ++revision
  clearTimeout(timer)
})
const toggleFavorite = async (row: DashboardShortcut) => {
  if (saving.value.has(row.id)) return
  saving.value.add(row.id)
  try {
    await storeApi({ id: row.id, type: 'panel' })
    if (alive) useEmitt().emitter.emit('dashboard-favorite-change', { id: row.id })
  } finally {
    saving.value.delete(row.id)
  }
}
const timeLabel = (time?: number) => {
  if (!time) return ''
  if (dayjs(time).isSame(dayjs(), 'day')) return `今天 ${dayjs(time).format('HH:mm')}`
  return dayjs(time).format('MM-DD HH:mm')
}
</script>

<template>
  <div class="dashboard-shortcuts" v-loading="loading || !ready" :aria-busy="loading || !ready">
    <div v-if="failed" class="shortcut-empty">
      <span>加载失败</span><el-button text @click="load">重试</el-button>
    </div>
    <template v-else-if="ready && !loading">
      <div v-if="!rows.length" class="shortcut-empty">
        {{
          keyword?.trim() ? '未找到匹配的仪表板' : mode === 'recent' ? '暂无最近查看' : '暂无收藏'
        }}
      </div>
      <div
        v-for="row in rows"
        :key="row.id"
        class="shortcut-row"
        :class="{ selected: String(selectedId) === row.id }"
      >
        <button
          class="shortcut-open"
          type="button"
          :title="[row.folder, row.name].filter(Boolean).join(' / ')"
          :aria-current="String(selectedId) === row.id ? 'page' : undefined"
          @click="emit('select', row)"
        >
          <el-icon class="shortcut-icon"
            ><Icon><dashboardIcon /></Icon
          ></el-icon>
          <span class="shortcut-text"
            ><span class="shortcut-name">{{ row.name }}</span
            ><small v-if="row.folder || row.viewedAt" class="shortcut-meta">{{
              [timeLabel(row.viewedAt), row.folder].filter(Boolean).join(' · ')
            }}</small></span
          >
        </button>
        <button
          type="button"
          class="shortcut-star"
          :class="{ favorited: row.favorite }"
          :disabled="saving.has(row.id)"
          :aria-label="`${row.favorite ? '取消收藏' : '收藏'}${row.name}`"
          :aria-pressed="row.favorite"
          @click.stop="toggleFavorite(row)"
        >
          <el-icon
            ><Icon><component :is="row.favorite ? favoriteIcon : emptyFavoriteIcon" /></Icon
          ></el-icon>
        </button>
      </div>
      <el-tooltip
        v-if="records.length >= 20"
        content="当前最多显示 20 项，可通过搜索查找其他仪表板。"
        placement="top"
        :enterable="false"
        ><button class="shortcut-limit" type="button" aria-label="查看显示数量说明">
          ⓘ
        </button></el-tooltip
      >
    </template>
  </div>
</template>

<style lang="less" scoped>
.dashboard-shortcuts {
  min-height: 110px;
  padding: 0 10px 12px;
}
.shortcut-row {
  display: flex;
  align-items: center;
  gap: 4px;
  min-height: 50px;
  margin: 3px 0;
  border-radius: 6px;
  &:hover {
    background: #f7f9fc;
  }
  &.selected {
    background: #ebf1f9;
    .shortcut-name {
      color: #48659d;
      font-weight: 500;
    }
  }
}
.shortcut-open {
  display: flex;
  align-items: center;
  flex: 1;
  gap: 9px;
  min-width: 0;
  padding: 9px;
  border: 0;
  background: transparent;
  color: #64748b;
  text-align: left;
  cursor: pointer;
}
.shortcut-icon {
  flex: 0 0 auto;
  color: #6d88af;
  font-size: 18px;
}
.shortcut-row.selected :deep(.scent-icon) {
  color: var(--scent-icon-active, #526fa8);
}
.shortcut-star.favorited :deep(path) {
  fill: currentColor;
}
.shortcut-text {
  flex: 1;
  min-width: 0;
}
.shortcut-name {
  display: block;
  overflow: hidden;
  font-size: 13px;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.shortcut-meta {
  display: block;
  overflow: hidden;
  margin-top: 3px;
  color: #98a4b6;
  font-size: 11px;
  line-height: 16px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.shortcut-star {
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 26px;
  width: 26px;
  height: 28px;
  margin-right: 4px;
  padding: 0;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: #a2adbd;
  font-size: 16px;
  cursor: pointer;
  &.favorited {
    color: #caa858;
  }
  &:hover {
    background: #e7edf7;
  }
  &:disabled {
    cursor: wait;
    opacity: 0.5;
  }
}
.shortcut-open:focus-visible,
.shortcut-star:focus-visible,
.shortcut-limit:focus-visible {
  outline: 2px solid #829bff;
  outline-offset: -2px;
  border-radius: 5px;
}
.shortcut-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 110px;
  gap: 8px;
  color: #98a4b6;
  font-size: 12px;
}
.shortcut-limit {
  display: block;
  margin: 10px auto 0;
  padding: 0;
  border: 0;
  background: transparent;
  color: #98a4b6;
  cursor: help;
}
</style>
