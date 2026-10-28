<script setup lang="ts">
import { computed, ref } from 'vue'
import FieldMore from './form/FieldMore.vue'
import DatasetIcon from './DatasetIcon.vue'
import { matchesField, fieldOriginalName } from './previewState'
const props = defineProps<{ fields: any[] }>()
const emit = defineEmits(['command', 'collapse', 'focus'])
const search = ref('')
const tab = ref('all')
const collapsed = ref<string[]>([])
const groups = computed(() =>
  ['d', 'q']
    .map(key => ({
      key,
      label: key === 'd' ? '维度' : '指标',
      fields: props.fields.filter(f => f.groupType === key && matchesField(f, search.value))
    }))
    .filter(g => (tab.value === 'all' || tab.value === g.key) && g.fields.length)
)
const toggle = (key: string) => {
  collapsed.value = collapsed.value.includes(key)
    ? collapsed.value.filter(x => x !== key)
    : [...collapsed.value, key]
}
</script>
<template>
  <aside class="dataset-field-library">
    <div class="library-head">
      <div class="library-title">
        <strong>字段库</strong><span>{{ fields.length }}</span
        ><button
          class="library-collapse"
          title="收起字段库"
          aria-label="收起字段库"
          @click="emit('collapse')"
        >
          <DatasetIcon name="collapse" />
        </button>
      </div>
      <el-input v-model="search" clearable placeholder="搜索字段名称或原始名" aria-label="搜索字段"
        ><template #prefix><DatasetIcon name="search" /></template
      ></el-input>
      <div class="library-tabs">
        <button
          v-for="item in [
            ['all', '全部'],
            ['d', '维度'],
            ['q', '指标']
          ]"
          :key="item[0]"
          :class="{ active: tab === item[0] }"
          @click="tab = item[0]"
        >
          {{ item[1] }}
        </button>
      </div>
    </div>
    <div class="library-scroll">
      <section v-for="group in groups" :key="group.key" class="library-group">
        <button
          class="library-group-title"
          :aria-expanded="!collapsed.includes(group.key) || !!search"
          @click="toggle(group.key)"
        >
          <DatasetIcon
            name="down"
            :class="{ folded: collapsed.includes(group.key) && !search }"
          /><span>{{ group.label }}</span
          ><small>{{ group.fields.length }}</small>
        </button>
        <div v-show="!collapsed.includes(group.key) || !!search">
          <div v-for="field in group.fields" :key="field.id" class="library-row">
            <button
              class="library-field"
              :title="[field.name, fieldOriginalName(field)].filter(Boolean).join(' · ')"
              @click="emit('focus', field)"
            >
              <span class="field-badge" :class="{ metric: group.key === 'q' }">{{
                field.extField === 2
                  ? 'ƒ'
                  : field.extField === 3
                  ? '▦'
                  : [2, 3, 4].includes(field.deType)
                  ? '#'
                  : field.deType === 1
                  ? '◷'
                  : 'Aa'
              }}</span
              ><span class="library-field-name"
                >{{ field.name
                }}<small v-if="fieldOriginalName(field)">{{
                  fieldOriginalName(field)
                }}</small></span
              >
            </button>
            <FieldMore
              :ext-field="field.extField"
              :show-time="field.deExtractType === 0"
              :trans-type="group.key === 'd' ? '转换为指标' : '转换为维度'"
              @handle-command="command => emit('command', field, command)"
            />
          </div>
        </div>
      </section>
      <div v-if="!groups.length" class="library-empty">没有匹配的字段</div>
    </div>
  </aside>
</template>
<style scoped lang="less">
.dataset-field-library {
  display: flex;
  flex-direction: column;
  width: 244px;
  height: 100%;
  min-height: 0;
  flex-shrink: 0;
  background: #fff;
  border-right: 1px solid #e4e8ef;
  color: #35445d;
}
.library-head {
  flex-shrink: 0;
  padding: 0 14px;
  border-bottom: 1px solid #edf0f5;
  background: #fff;
}
.library-title {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 52px;
  margin-bottom: 0;
  font-size: 13px;
  span {
    font-size: 11px;
    color: #96a0af;
  }
}
button {
  font: inherit;
  cursor: pointer;
}
.library-collapse {
  margin-left: auto;
  background: none;
  border: 0;
  color: #8f9aaa;
  padding: 4px;
  border-radius: 4px;
  &:hover {
    background: #edf1f7;
    color: #3e5475;
  }
}
.library-tabs {
  display: flex;
  gap: 6px;
  padding: 12px 0;
  button {
    border: 0;
    background: none;
    padding: 5px 10px;
    border-radius: 4px;
    color: #8a96a8;
    font-size: 12px;
    &.active {
      background: #edf1f7;
      color: #3e5475;
      font-weight: 600;
    }
  }
}
.library-scroll {
  flex: 1;
  min-height: 0;
  overflow: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
  padding: 0 8px 14px;
  isolation: isolate;
}
.library-group-title {
  position: relative;
  width: 100%;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 12px 10px;
  background: white;
  border: 0;
  color: #7c899c;
  text-align: left;
  font-size: 12px;
  .dataset-icon {
    width: 12px;
    height: 12px;
  }
  .folded {
    transform: rotate(-90deg);
  }
  small {
    margin-left: auto;
    color: #a0aaba;
  }
}
.library-row {
  display: flex;
  align-items: center;
  min-height: 40px;
  border-radius: 5px;
  padding-right: 4px;
  &:hover,
  &:focus-within {
    background: #edf1f7;
  }
  :deep(.menu-more) {
    color: #8f9aaa;
    flex-shrink: 0;
  }
}
.library-field {
  flex: 1;
  min-width: 0;
  display: flex;
  gap: 10px;
  align-items: center;
  border: 0;
  background: none;
  text-align: left;
  padding: 8px 9px;
  color: #35445d;
}
.field-badge {
  font-size: 12px;
  color: #8b9bbc;
  width: 20px;
  flex-shrink: 0;
  text-align: center;
  &.metric {
    color: #5b998d;
    font-size: 16px;
  }
}
.library-field-name {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  line-height: 20px;
  small {
    display: block;
    font: 10px/18px Consolas, monospace;
    color: #98a4b6;
    overflow: hidden;
    text-overflow: ellipsis;
  }
}
.library-empty {
  padding: 30px 12px;
  text-align: center;
  color: #96a0af;
  font-size: 12px;
}
</style>
