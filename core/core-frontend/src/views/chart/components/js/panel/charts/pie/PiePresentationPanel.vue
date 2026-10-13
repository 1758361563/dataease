<script setup lang="ts">
import { computed, ref, watch, nextTick } from 'vue'
const props = defineProps<{
  plotId: string
  ranking: boolean
  compact: boolean
  rows: any[]
  total: number
  categoryCount: number
  excluded: number
  activeKey: string | null
  members: any[]
  detailsOpen: boolean
  color: (datum: any) => string
  format: (value: number) => string
  decimals: number
  textColor: string
}>()
const emit = defineEmits(['hover', 'select', 'closeDetails'])
const list = ref<HTMLElement>()
const active = computed(() => props.rows.find(row => row.pieKey === props.activeKey))
const percentage = (value: number) =>
  `${(props.total > 0 ? (value / props.total) * 100 : 0).toFixed(props.decimals)}%`
const name = (row: any) => {
  const text = row.field == null ? '未分类' : row.field === '' ? '（空字符串）' : String(row.field)
  return row.pieMembers ? `${text}（${row.pieMembers.length} 类）` : text
}
const hover = (row: any) => emit('hover', row?.pieKey || null)
watch(
  () => props.activeKey,
  async key => {
    await nextTick()
    const index = props.rows.findIndex(row => row.pieKey === key)
    const button = list.value?.children[index] as HTMLElement
    const top = button ? button.offsetTop - list.value.offsetTop : 0
    if (
      button &&
      (top < list.value.scrollTop ||
        top + button.offsetHeight > list.value.scrollTop + list.value.clientHeight)
    ) {
      list.value.scrollTop = top
    }
  }
)
</script>

<template>
  <div
    class="pie-presentation"
    :class="{ ranking, compact }"
    :style="{ color: textColor }"
    @keydown.esc="emit('closeDetails')"
  >
    <div class="pie-ring">
      <div :id="plotId" class="pie-plot"></div>
      <div v-if="ranking" class="pie-center" aria-live="polite">
        <span :title="active ? name(active) : '当前查询结果总计'">{{
          active ? name(active) : '总计'
        }}</span>
        <strong>{{ active ? percentage(active.value) : format(total) }}</strong>
        <small>{{ active ? format(active.value) : `${categoryCount} 个分类` }}</small>
      </div>
    </div>
    <section v-if="ranking" class="pie-ranking" aria-label="分类榜单">
      <header>
        <strong>分类明细 · {{ rows.length }} 项</strong><span>占比 ↓</span>
      </header>
      <div ref="list" class="pie-list" @mouseleave="hover(null)">
        <button
          v-for="(row, index) in rows"
          :key="row.pieKey"
          type="button"
          :class="{ active: activeKey === row.pieKey }"
          :title="`${name(row)} · ${format(row.value)} · ${percentage(row.value)}`"
          @mouseenter="hover(row)"
          @focus="hover(row)"
          @blur="hover(null)"
          @click="emit('select', row, $event)"
        >
          <span class="pie-rank">{{ String(index + 1).padStart(2, '0') }}</span>
          <span class="pie-row-main"
            ><span class="pie-row-title"
              ><i :style="{ background: color(row) }"></i><span>{{ name(row) }}</span></span
            >
            <span class="pie-progress"
              ><i :style="{ width: percentage(row.value), background: color(row) }"></i
            ></span>
          </span>
          <span class="pie-share">{{ percentage(row.value) }}</span>
        </button>
      </div>
      <small v-if="excluded" class="pie-excluded">{{ excluded }} 项负值或无效值未参与占比</small>
    </section>
    <section v-if="detailsOpen" class="pie-details" aria-label="其他分类明细">
      <header>
        <strong>其他明细 · {{ members.length }} 项</strong
        ><button type="button" aria-label="关闭其他明细" @click="emit('closeDetails')">×</button>
      </header>
      <p>占比按当前查询结果总计计算，点击真实分类执行图表交互。</p>
      <div class="pie-list">
        <button
          v-for="row in members"
          :key="row.pieKey"
          type="button"
          @click="emit('select', row, $event)"
          :title="`${name(row)} · ${format(row.value)}`"
        >
          <span class="pie-row-main">{{ name(row) }}</span
          ><span class="pie-share">{{ percentage(row.value) }}</span>
        </button>
      </div>
    </section>
  </div>
</template>

<style scoped lang="less">
.pie-presentation {
  width: 100%;
  height: 100%;
  position: relative;
  display: flex;
  min-height: 0;
  font-family: inherit;
  font-size: 12px;
}
.pie-ring {
  position: relative;
  flex: 1;
  min-width: 0;
  min-height: 0;
}
.pie-plot {
  position: absolute;
  inset: 0;
}
.ranking .pie-ring {
  flex: 0 0 46%;
}
.pie-center {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  pointer-events: none;
  gap: 6px;
  span,
  small {
    max-width: 48%;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    opacity: 0.7;
  }
  strong {
    max-width: 50%;
    overflow: hidden;
    text-overflow: ellipsis;
    font-size: clamp(18px, 2vw, 30px);
    font-variant-numeric: tabular-nums;
  }
}
.pie-ranking {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  border-left: 1px solid rgba(148, 163, 184, 0.2);
  padding: 12px;
}
header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 28px;
  margin-bottom: 8px;
  span {
    opacity: 0.6;
  }
}
.pie-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  scrollbar-width: thin;
  button {
    width: 100%;
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 10px 6px;
    border: 0;
    border-radius: 5px;
    color: inherit;
    background: transparent;
    text-align: left;
    cursor: pointer;
    font: inherit;
    &:hover,
    &.active,
    &:focus-visible {
      background: rgba(148, 163, 184, 0.13);
    }
    &:focus-visible {
      outline: 2px solid #5470c6;
      outline-offset: -2px;
    }
  }
}
.pie-rank {
  width: 22px;
  flex-shrink: 0;
  opacity: 0.5;
  font-variant-numeric: tabular-nums;
}
.pie-row-main {
  flex: 1;
  min-width: 0;
  overflow-wrap: anywhere;
}
.pie-row-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 7px;
  span {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  i {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    flex-shrink: 0;
  }
}
.pie-progress {
  display: block;
  height: 4px;
  background: rgba(148, 163, 184, 0.18);
  border-radius: 3px;
  i {
    display: block;
    height: 100%;
    border-radius: inherit;
  }
}
.pie-share {
  flex-shrink: 0;
  min-width: 54px;
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.pie-excluded {
  padding-top: 6px;
  opacity: 0.6;
}
.compact.ranking {
  flex-direction: column;
  .pie-ring {
    flex: 0 0 44%;
  }
  .pie-ranking {
    border-left: 0;
    border-top: 1px solid rgba(148, 163, 184, 0.2);
    padding: 6px 10px;
  }
}
.pie-details {
  position: absolute;
  right: 8px;
  top: 8px;
  bottom: 8px;
  width: min(420px, calc(100% - 16px));
  z-index: 4;
  display: flex;
  flex-direction: column;
  padding: 12px;
  background: var(--ed-bg-color, #fff);
  color: var(--ed-text-color-primary, #1e293b);
  border: 1px solid rgba(148, 163, 184, 0.3);
  border-radius: 8px;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.12);
  header button {
    border: 0;
    background: transparent;
    color: inherit;
    cursor: pointer;
    font-size: 22px;
  }
  p {
    margin: 0 0 8px;
    opacity: 0.6;
    line-height: 1.5;
  }
}
</style>
