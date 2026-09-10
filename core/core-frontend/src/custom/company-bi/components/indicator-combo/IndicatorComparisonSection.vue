<script setup lang="ts">
import { PropType } from 'vue'

export type ComparisonItem = {
  key: string
  label: string
  text: string
  hint?: string
  trend: 'up' | 'down' | 'flat'
}

defineProps({
  items: {
    type: Array as PropType<ComparisonItem[]>,
    default: () => []
  },
  layout: {
    type: String as PropType<'columns' | 'row' | 'list'>,
    default: 'columns'
  },
  align: {
    type: String as PropType<'center' | 'left'>,
    default: 'center'
  },
  showDivider: {
    type: Boolean,
    default: true
  }
})
</script>

<template>
  <template v-if="items.length">
    <div v-if="showDivider" class="bi-indicator-divider" aria-hidden="true" />
    <div class="bi-indicator-compares" :class="['is-layout-' + layout, 'is-align-' + align]">
      <div
        v-for="item in items"
        :key="item.key"
        class="bi-indicator-compare"
        :title="item.hint"
        tabindex="0"
        :aria-label="[item.label, item.text, item.hint].join(' ')"
      >
        <div class="bi-indicator-compare__label">{{ item.label }}</div>
        <div class="bi-indicator-compare__value" :class="'is-' + item.trend">
          <span>{{ item.text }}</span>
          <!-- 观远风格实心三角：升 ▲ / 降 ▼；升绿降红 -->
          <span v-if="item.trend === 'up'" class="bi-indicator-compare__arrow" aria-hidden="true"
            >▲</span
          >
          <span
            v-else-if="item.trend === 'down'"
            class="bi-indicator-compare__arrow"
            aria-hidden="true"
            >▼</span
          >
        </div>
      </div>
    </div>
  </template>
</template>
