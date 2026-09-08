<script setup lang="ts">
import { getData } from '@/api/chart'
import { ref, reactive, shallowRef, computed, CSSProperties, toRefs, PropType } from 'vue'
import { dvMainStoreWithOut } from '@/store/modules/data-visualization/dvMain'
import { customAttrTrans, customStyleTrans, recursionTransObj } from '@/utils/canvasStyle'
import { deepCopy, isMobile } from '@/utils/utils'
import { cloneDeep, defaultsDeep, defaultTo } from 'lodash-es'
import {
  BASE_VIEW_CONFIG,
  CHART_FONT_FAMILY_MAP,
  DEFAULT_INDICATOR_NAME_STYLE,
  DEFAULT_INDICATOR_STYLE
} from '@/views/chart/components/editor/util/chart'
import { valueFormatter } from '@/views/chart/components/js/formatter'
import { storeToRefs } from 'pinia'
import { isDashboard, trackBarStyleCheck } from '@/utils/canvasUtils'
import ViewTrackBar from '@/components/visualization/ViewTrackBar.vue'
import { hasNextDrillLevel } from '@/views/chart/components/views/util/drill'
import { ElMessage } from 'element-plus-secondary'
import { useI18n } from '@/hooks/web/useI18n'
import { DEFAULT_INDICATOR_COMBO_LAYOUT } from './defaults'
import IndicatorMainBlock from './IndicatorMainBlock.vue'
import IndicatorComparisonSection from './IndicatorComparisonSection.vue'
import { indicatorDisplayName } from './displayName'
import {
  cloneViewWithShiftedDateFilter,
  compareGroupKey,
  computeCompareResult,
  stripCompareCalc
} from './guandataDateFilterCompare'

/**
 * 对比指标卡（company）
 *
 * 内部约定（不对用户暴露）：
 * - yAxis[0]：中央主展示
 * - yAxis[1..]：底部对比项（仅渲染已配置 compareCalc 的）
 *
 * 取数对齐观远「基于日期筛选」：
 * - 本期：整段外部日期筛选聚合（无日期维、无官方同环比）
 * - 上期：同一筛选起止按对比类型整体偏移后再聚合
 * - 增长率：(本期 - 上期) / 上期
 */
const { t } = useI18n()

const props = defineProps({
  commonParams: {
    type: Object,
    required: false
  },
  element: {
    type: Object,
    default() {
      return {
        propValue: null
      }
    }
  },
  view: {
    type: Object as PropType<ChartObj>,
    default() {
      return {
        propValue: null
      }
    }
  },
  showPosition: {
    type: String,
    required: false,
    default: 'canvas'
  },
  scale: {
    type: Number,
    required: false,
    default: 1
  },
  terminal: {
    type: String,
    default: 'pc'
  },
  suffixId: {
    type: String,
    required: false,
    default: 'common'
  },
  fontFamily: {
    type: String,
    required: false,
    default: 'inherit'
  }
})

const { view, scale, terminal, showPosition, commonParams } = toRefs(props)

const dvMainStore = dvMainStoreWithOut()
const dataVMobile = !isDashboard() && isMobile()
const { embeddedCallBack, nowPanelTrackInfo, nowPanelJumpInfo, mobileInPc, inMobile } =
  storeToRefs(dvMainStore)
const viewTrack = ref(null)
const indicatorRef = ref(null)
const errMsg = ref('')
const isError = ref(false)
const drillFilters = ref([])
const state = reactive({
  pointParam: null,
  loading: false,
  trackBarStyle: {
    position: 'absolute',
    left: '50%',
    top: '50%'
  }
})

const chartData = shallowRef<Partial<Chart['data']>>({
  fields: []
})

const seriesOrderMap = ref<number[]>([])

const hasCompare = quota => {
  const type = quota?.compareCalc?.type
  return !!type && type !== 'none'
}

const seriesAt = origIdx => {
  const list = chartData.value?.series
  if (!list || !list.length) return undefined
  const order = seriesOrderMap.value
  const sIdx = order.length ? order.indexOf(origIdx) : origIdx
  return list[sIdx]
}

const firstValueOf = origIdx => {
  const data = seriesAt(origIdx)?.data
  if (data && data.length > 0) return data[0]
  return undefined
}

const displayNameOf = quota => indicatorDisplayName(quota)

const resultName = computed(() => {
  if (view.value?.yAxis?.length) {
    return displayNameOf(view.value.yAxis[0])
  }
  return ''
})

const result = computed(() => {
  let _result = firstValueOf(0)
  if (_result === null || _result === undefined || _result === '') {
    if (view.value.senior && view.value.senior?.functionCfg?.emptyDataStrategy === 'setZero') {
      _result = 0
    } else {
      return '-'
    }
  }
  return _result
})

const layoutCfg = computed(() =>
  defaultsDeep(
    cloneDeep(view.value?.customAttr?.indicatorCombo || {}),
    cloneDeep(DEFAULT_INDICATOR_COMBO_LAYOUT)
  )
)

const comparePills = computed(() => {
  const quotas = view.value?.yAxis || []
  const pills = []
  quotas.forEach((quota, origIdx) => {
    if (origIdx === 0 || !hasCompare(quota)) return
    pills.push(buildPill(quota, firstValueOf(origIdx), origIdx))
  })
  return pills
})

const buildPill = (quota, raw, origIdx) => {
  // 对比项标签：优先用户别名；否则用观远简称（勿用字段名「吊牌额」，会与主标题重复）
  const guandataTypeLabel: Record<string, string> = {
    day_mom: '环比',
    week_yoy: '周同比',
    week_mom: '周环比',
    month_yoy: '月同比',
    month_mom: '月环比',
    year_yoy: '年同比',
    year_mom: '年环比'
  }
  const compareType = quota.compareCalc?.type
  const alias = (quota.chartShowName || '').trim()
  const fieldName = (quota.name || '').trim()
  // 拖入后 chartShowName 常等于字段名；此时仍用观远简称，只有用户改过别名才覆盖
  const effectiveAlias = alias && alias !== fieldName ? alias : ''
  const label =
    effectiveAlias ||
    guandataTypeLabel[compareType] ||
    (compareType ? t('chart.' + compareType) : '')
  const resultData = quota.compareCalc.resultData
  const key = quota.metricInstanceId || `${quota.id}-${origIdx}`
  if (raw === null || raw === undefined || raw === '') {
    return { key, label, text: '-', trend: 'flat' as const }
  }
  const n = Number(raw)
  if (Number.isNaN(n)) {
    return { key, label, text: '-', trend: 'flat' as const }
  }
  // 上期为 0 且本期非 0：观远侧常显「无限」
  if (!Number.isFinite(n)) {
    return { key, label, text: '∞', trend: 'up' as const }
  }
  const trend = n > 0 ? ('up' as const) : n < 0 ? ('down' as const) : ('flat' as const)
  if (resultData === 'percent') {
    return {
      key,
      label,
      text: `${n > 0 ? '+' : ''}${(n * 100).toFixed(2)}%`,
      trend
    }
  }
  if (resultData === 'sub') {
    return {
      key,
      label,
      text: `${n > 0 ? '+' : ''}${valueFormatter(n, quota.formatterCfg)}`,
      trend
    }
  }
  return {
    key,
    label,
    text: valueFormatter(n, quota.formatterCfg),
    trend: 'flat' as const
  }
}

const indicatorColor = ref(DEFAULT_INDICATOR_STYLE.color)
const thresholdColor = computed(() => {
  let color: string = indicatorColor.value
  let backgroundColor: string = DEFAULT_INDICATOR_STYLE.backgroundColor
  if (result.value === '-') {
    return { color, backgroundColor }
  }
  const value = result.value
  if (
    view.value.senior &&
    view.value.senior.threshold?.enable &&
    view.value.senior.threshold?.labelThreshold?.length > 0
  ) {
    const senior = view.value.senior
    for (let i = 0; i < senior.threshold.labelThreshold.length; i++) {
      let flag = false
      const th = senior.threshold.labelThreshold[i]
      const tv = th.value
      if (th.term === 'eq' && value === tv) flag = true
      else if (th.term === 'not_eq' && value !== tv) flag = true
      else if (th.term === 'lt' && value < tv) flag = true
      else if (th.term === 'gt' && value > tv) flag = true
      else if (th.term === 'le' && value <= tv) flag = true
      else if (th.term === 'ge' && value >= tv) flag = true
      else if (th.term === 'between' && th.min <= value && value <= th.max) flag = true
      if (flag) {
        color = th.color
        backgroundColor = th.backgroundColor
        break
      }
    }
  }
  return { color, backgroundColor }
})

const formattedResult = computed(() => {
  let _result = result.value
  if (_result === '-') return _result
  if (view.value.yAxis?.[0]?.formatterCfg) {
    return valueFormatter(_result, view.value.yAxis[0].formatterCfg)
  }
  return _result
})

const emit = defineEmits([
  'onPointClick',
  'onChartClick',
  'onDrillFilters',
  'onJumpClick',
  'onComponentEvent'
])

const contentStyle = ref<CSSProperties>({
  display: 'flex',
  'flex-direction': 'column',
  'align-items': 'center',
  'justify-content': 'center',
  height: '100%',
  'background-color': thresholdColor.value.backgroundColor
})

const indicatorClass = ref<CSSProperties>({
  color: thresholdColor.value.color,
  'font-size': DEFAULT_INDICATOR_STYLE.fontSize + 'px',
  'font-family': defaultTo(
    props.fontFamily,
    CHART_FONT_FAMILY_MAP[DEFAULT_INDICATOR_STYLE.fontFamily]
  ),
  'font-weight': DEFAULT_INDICATOR_STYLE.isBolder ? 'bold' : 'normal',
  'font-style': DEFAULT_INDICATOR_STYLE.isItalic ? 'italic' : 'normal',
  'letter-spacing': DEFAULT_INDICATOR_STYLE.letterSpace + 'px',
  'font-synthesis': 'weight style'
})

const indicatorSuffixClass = ref<CSSProperties>({
  color: DEFAULT_INDICATOR_STYLE.suffixColor,
  'font-size': DEFAULT_INDICATOR_STYLE.suffixFontSize + 'px',
  'font-family': defaultTo(
    props.fontFamily,
    CHART_FONT_FAMILY_MAP[DEFAULT_INDICATOR_STYLE.fontFamily]
  ),
  'font-weight': DEFAULT_INDICATOR_STYLE.suffixIsBolder ? 'bold' : 'normal',
  'font-style': DEFAULT_INDICATOR_STYLE.suffixIsItalic ? 'italic' : 'normal',
  'letter-spacing': DEFAULT_INDICATOR_STYLE.suffixLetterSpace + 'px',
  'font-synthesis': 'weight style'
})

const showSuffix = ref<boolean>(false)
const suffixContent = ref('')
const indicatorNameShow = ref(true)
const indicatorNameClass = ref<CSSProperties>({
  color: DEFAULT_INDICATOR_NAME_STYLE.color,
  'font-size': DEFAULT_INDICATOR_NAME_STYLE.fontSize + 'px',
  'font-family': defaultTo(
    props.fontFamily,
    CHART_FONT_FAMILY_MAP[DEFAULT_INDICATOR_STYLE.fontFamily]
  ),
  'font-weight': DEFAULT_INDICATOR_NAME_STYLE.isBolder ? 'bold' : 'normal',
  'font-style': DEFAULT_INDICATOR_NAME_STYLE.isItalic ? 'italic' : 'normal',
  'letter-spacing': DEFAULT_INDICATOR_NAME_STYLE.letterSpace + 'px',
  'font-synthesis': 'weight style'
})

const renderChart = async viewArg => {
  if (!viewArg) return

  const TEMP_DEFAULT_CHART = cloneDeep(BASE_VIEW_CONFIG)
  delete TEMP_DEFAULT_CHART.customAttr.basicStyle.alpha

  const chart = deepCopy({
    ...defaultsDeep(viewArg, TEMP_DEFAULT_CHART),
    data: chartData.value
  }) as ChartObj

  recursionTransObj(customAttrTrans, chart.customAttr, scale.value, terminal.value)
  recursionTransObj(customStyleTrans, chart.customStyle, scale.value, terminal.value)

  if (chart.customAttr) {
    const { indicator, indicatorName } = chart.customAttr
    // 主值区始终整体居中；底部对齐由 layoutCfg.align 控制
    contentStyle.value['align-items'] = 'center'
    contentStyle.value['justify-content'] = 'center'

    if (indicator) {
      indicatorColor.value = indicator.color
      indicatorClass.value = {
        color: thresholdColor.value.color,
        'font-size': indicator.fontSize + 'px',
        'font-family': defaultTo(
          indicator.fontFamily,
          CHART_FONT_FAMILY_MAP[DEFAULT_INDICATOR_STYLE.fontFamily]
        ),
        'font-weight': indicator.isBolder ? 'bold' : 'normal',
        'font-style': indicator.isItalic ? 'italic' : 'normal',
        'letter-spacing': indicator.letterSpace + 'px',
        'font-synthesis': 'weight style'
      }
      contentStyle.value['background-color'] = thresholdColor.value.backgroundColor
      indicatorSuffixClass.value = {
        color: indicator.suffixColor,
        'font-size': indicator.suffixFontSize + 'px',
        'font-family': defaultTo(
          indicator.suffixFontFamily,
          CHART_FONT_FAMILY_MAP[DEFAULT_INDICATOR_STYLE.suffixFontFamily]
        ),
        'font-weight': indicator.suffixIsBolder ? 'bold' : 'normal',
        'font-style': indicator.suffixIsItalic ? 'italic' : 'normal',
        'letter-spacing': indicator.suffixLetterSpace + 'px',
        'font-synthesis': 'weight style'
      }
      showSuffix.value = indicator.suffixEnable
      suffixContent.value = defaultTo(indicator.suffix, '')
    }

    if (indicatorName?.show !== false) {
      indicatorNameShow.value = true
      indicatorNameClass.value = {
        color: indicatorName?.color || DEFAULT_INDICATOR_NAME_STYLE.color,
        'font-size': (indicatorName?.fontSize || DEFAULT_INDICATOR_NAME_STYLE.fontSize) + 'px',
        'font-family': defaultTo(
          indicatorName?.fontFamily,
          CHART_FONT_FAMILY_MAP[DEFAULT_INDICATOR_NAME_STYLE.fontFamily]
        ),
        'font-weight': indicatorName?.isBolder ? 'bold' : 'normal',
        'font-style': indicatorName?.isItalic ? 'italic' : 'normal',
        'letter-spacing': (indicatorName?.letterSpace || 0) + 'px',
        'font-synthesis': 'weight style'
      }
    } else {
      indicatorNameShow.value = false
    }
  }
}

/**
 * 观远 DATE_FILTER 取数：
 * 1) 本期：全部指标关掉官方同环比，按当前筛选聚合
 * 2) 上期：按 (日期字段 + 对比类型) 分组，筛选起止整体偏移后再聚合
 * 3) 前端按 resultData 算增长率 / 差值 / 上期值
 */
const calcData = (viewArg, callback) => {
  if (viewArg.tableId || viewArg['dataFrom'] === 'template') {
    state.loading = true
    isError.value = false
    errMsg.value = ''

    const base = JSON.parse(JSON.stringify(viewArg))
    base.type = 'indicator'
    const quotas = base.yAxis || []
    seriesOrderMap.value = quotas.map((_, i) => i)

    const currentReq = {
      ...base,
      yAxis: quotas.map(q => stripCompareCalc(q)).filter(q => q.id)
    }

    type CompareMeta = { origIdx: number; quota: any }
    const compareMeta: CompareMeta[] = []
    quotas.forEach((q, i) => {
      if (i > 0 && hasCompare(q)) {
        compareMeta.push({ origIdx: i, quota: q })
      }
    })

    // 相同 (日期字段 + 对比类型) 共用一次上期请求
    const groups = new Map<string, { fieldId: string; type: string; items: CompareMeta[] }>()
    compareMeta.forEach(item => {
      const fieldId = item.quota?.compareCalc?.field
      const type = item.quota?.compareCalc?.type
      if (!fieldId || !type) return
      const key = compareGroupKey(item.quota)
      if (!groups.has(key)) {
        groups.set(key, { fieldId, type, items: [] })
      }
      groups.get(key).items.push(item)
    })

    type PrevTask = {
      key: string
      items: CompareMeta[]
      promise: Promise<any> | null
    }
    const prevTasks: PrevTask[] = []
    groups.forEach((g, key) => {
      const shifted = cloneViewWithShiftedDateFilter(base, g.fieldId, g.type)
      if (!shifted) {
        prevTasks.push({ key, items: g.items, promise: null })
        return
      }
      const prevReq = {
        ...shifted,
        yAxis: g.items.map(it => stripCompareCalc(it.quota))
      }
      prevTasks.push({ key, items: g.items, promise: getData(prevReq) })
    })

    const tasks: Promise<any>[] = [getData(currentReq)]
    prevTasks.forEach(pt => {
      if (pt.promise) tasks.push(pt.promise)
    })

    Promise.all(tasks)
      .then(results => {
        const currentRes = results[0]
        if (currentRes?.code && currentRes.code !== 0) {
          isError.value = true
          errMsg.value = currentRes.msg
          callback?.()
          return
        }

        let ri = 1
        const prevByOrigIdx = new Map<number, unknown>()
        for (const pt of prevTasks) {
          if (!pt.promise) continue
          const prevRes = results[ri++]
          if (prevRes?.code && prevRes.code !== 0) {
            isError.value = true
            errMsg.value = prevRes.msg
            callback?.()
            return
          }
          pt.items.forEach((it, j) => {
            prevByOrigIdx.set(it.origIdx, prevRes?.data?.series?.[j]?.data?.[0])
          })
        }

        const series = quotas.map(() => ({ data: [undefined] }))
        const currentSeries = currentRes?.data?.series || []
        quotas.forEach((_, i) => {
          series[i] = { data: [currentSeries[i]?.data?.[0]] }
        })

        compareMeta.forEach(it => {
          const hadPrevTask = prevTasks.find(p => p.items.some(x => x.origIdx === it.origIdx))
          if (!hadPrevTask?.promise) {
            // 无匹配的外部日期筛选 → 无法算观远 DATE_FILTER 对比
            series[it.origIdx] = { data: [undefined] }
            return
          }
          // 同字段重复拖入时后端可能只回一条 series，回退到主指标本期值
          const currentVal = currentSeries[it.origIdx]?.data?.[0] ?? currentSeries[0]?.data?.[0]
          const previousVal = prevByOrigIdx.get(it.origIdx)
          const resultData = it.quota?.compareCalc?.resultData || 'percent'
          series[it.origIdx] = {
            data: [computeCompareResult(currentVal, previousVal, resultData)]
          }
        })

        chartData.value = {
          fields: currentRes?.data?.fields || [],
          series
        } as Partial<Chart['data']>
        drillFilters.value = currentRes?.drillFilters || []
        emit('onDrillFilters', currentRes?.drillFilters)
        dvMainStore.setViewDataDetails(viewArg.id, currentRes)
        renderChart(viewArg)
        callback?.()
      })
      .catch(error => {
        isError.value = true
        errMsg.value = error?.message || String(error || '')
        drillFilters.value = []
        callback?.()
      })
  } else {
    drillFilters.value = []
    callback?.()
  }
}

const trackClick = trackAction => {
  const param = state.pointParam
  if (!param?.data?.dimensionList && !param?.data?.quotaList) return
  const linkageParam = {
    option: 'linkage',
    innerType: 'indicator',
    name: state.pointParam.data.name,
    viewId: view.value.id,
    dimensionList: state.pointParam.data.dimensionList,
    quotaList: state.pointParam.data.quotaList,
    customFilter: state.pointParam.data.customFilter
  }
  const jumpParam = {
    option: 'jump',
    innerType: 'indicator',
    name: state.pointParam.data.name,
    viewId: view.value.id,
    dimensionList: state.pointParam.data.dimensionList,
    quotaList: state.pointParam.data.quotaList,
    sourceType: state.pointParam.data.sourceType
  }
  const clickParams = {
    option: 'pointClick',
    innerType: 'indicator',
    name: state.pointParam.data.name,
    viewId: view.value.id,
    dimensionList: state.pointParam.data.dimensionList,
    quotaList: state.pointParam.data.quotaList,
    customFilter: state.pointParam.data.customFilter
  }
  switch (trackAction) {
    case 'pointClick':
      emit('onPointClick', clickParams)
      break
    case 'linkageAndDrill':
      dvMainStore.addViewTrackFilter(linkageParam)
      emit('onChartClick', param)
      break
    case 'drill':
      emit('onChartClick', param)
      break
    case 'linkage':
      dvMainStore.addViewTrackFilter(linkageParam)
      break
    case 'jump':
      if (mobileInPc.value && !inMobile.value) return
      emit('onJumpClick', jumpParam)
      break
    case 'event_jump':
    case 'event_download':
    case 'event_share':
    case 'event_fullScreen':
    case 'event_showHidden':
    case 'event_refreshDataV':
    case 'event_refreshView':
      emit('onComponentEvent', jumpParam)
      break
    default:
      break
  }
}

const trackMenu = computed(() => {
  let trackMenuInfo = []
  if (showPosition.value === 'viewDialog') return trackMenuInfo
  let linkageCount = 0
  let jumpCount = 0
  chartData.value?.fields?.forEach(item => {
    const sourceInfo = view.value.id + '#' + item.id
    if (nowPanelTrackInfo.value[sourceInfo]) linkageCount++
    if (nowPanelJumpInfo.value[sourceInfo]) jumpCount++
  })
  jumpCount &&
    view.value?.jumpActive &&
    (!mobileInPc.value || inMobile.value) &&
    trackMenuInfo.push('jump')
  linkageCount && view.value?.linkageActive && trackMenuInfo.push('linkage')
  hasNextDrillLevel(view.value.drillFields, drillFilters.value.length) &&
    trackMenuInfo.push('drill')
  if (trackMenuInfo.length === 3 && props.element.actionSelection.linkageActive === 'auto') {
    trackMenuInfo = ['jump', 'linkageAndDrill']
  } else if (
    trackMenuInfo.length === 2 &&
    props.element.actionSelection.linkageActive === 'auto' &&
    !trackMenuInfo.includes('jump')
  ) {
    trackMenuInfo = ['linkageAndDrill']
  }
  if (commonParams.value?.eventEnable) {
    trackMenuInfo.push('event_' + commonParams.value?.eventType)
  }
  return trackMenuInfo
})

const showCursor = computed(() => trackMenu.value.length || embeddedCallBack.value === 'yes')

const pointClickTrans = () => {
  if (embeddedCallBack.value === 'yes') trackClick('pointClick')
}

const action = param => {
  state.pointParam = param
  pointClickTrans()
  if (trackMenu.value.length < 2) {
    if (view.value.drillFields.length > 0 && trackMenu.value.length === 0) {
      ElMessage.error(t('chart.last_layer'))
      return
    }
    trackClick(trackMenu.value[0])
  } else {
    setTimeout(() => {
      const barStyleTemp = { left: param.x - 50, top: param.y + 10 }
      trackBarStyleCheck(props.element, barStyleTemp, props.scale, trackMenu.value.length)
      if (dataVMobile) {
        state.trackBarStyle.left = barStyleTemp.left + 40 + 'px'
        state.trackBarStyle.top = barStyleTemp.top + 70 + 'px'
      } else {
        state.trackBarStyle.left = barStyleTemp.left + 'px'
        state.trackBarStyle.top = barStyleTemp.top + 'px'
      }
      viewTrack.value.trackButtonClick()
    }, 200)
  }
}

const onPointClick = event => {
  if (view.value?.yAxis?.length) {
    const axis = view.value.yAxis[0]
    const rect = indicatorRef.value.getBoundingClientRect()
    const params = {
      x: event.clientX - rect.left,
      y: event.clientY - rect.top,
      data: {
        name: axis.name,
        dimensionList: view.value.xAxis,
        quotaList: view.value.yAxis,
        customFilter: view.value.customFilter
      }
    }
    action(params)
  }
}

defineExpose({ calcData, renderChart, trackMenu })
</script>

<template>
  <div
    ref="indicatorRef"
    class="bi-indicator-shell bi-combo"
    :class="{ 'menu-point': showCursor }"
    :style="contentStyle"
    @mouseup="onPointClick"
  >
    <chart-error v-if="isError" :err-msg="errMsg" />

    <template v-else>
      <view-track-bar
        ref="viewTrack"
        :track-menu="trackMenu"
        :font-family="fontFamily"
        class="track-bar"
        :style="state.trackBarStyle"
        @trackClick="trackClick"
        :is-data-v-mobile="dataVMobile"
      />

      <indicator-main-block
        :label="resultName"
        :value="formattedResult"
        :suffix="suffixContent"
        :show-label="indicatorNameShow"
        :show-suffix="showSuffix"
        :label-style="indicatorNameClass"
        :value-style="indicatorClass"
        :suffix-style="indicatorSuffixClass"
      />

      <indicator-comparison-section
        :items="comparePills"
        :layout="layoutCfg.layout"
        :align="layoutCfg.align"
        :show-divider="layoutCfg.showDivider"
      />
    </template>
  </div>
</template>

<style scoped lang="less">
.menu-point {
  cursor: pointer;
}
</style>
