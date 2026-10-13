import { getExplicitChartTooltip } from '@/views/chart/components/js/panel/common/common_antv'
import { visibleTooltipField } from '@/views/chart/components/js/tooltipFieldIdentity'
import {
  G2PlotChartView,
  G2PlotDrawOptions
} from '@/views/chart/components/js/panel/types/impl/g2plot'
import type { Pie as G2Pie, PieOptions } from '@antv/g2plot/esm/plots/pie'
import {
  flow,
  hexColorToRGBA,
  parseJson,
  setUpSingleDimensionSeriesColor
} from '@/views/chart/components/js/util'
import {
  configPlotTooltipEvent,
  getPadding,
  getTooltipContainer,
  getTooltipSeriesTotalMap,
  TOOLTIP_TPL
} from '@/views/chart/components/js/panel/common/common_antv'
import { valueFormatter, formatterItem } from '@/views/chart/components/js/formatter'
import {
  PIE_AXIS_CONFIG,
  PIE_AXIS_TYPE,
  PIE_EDITOR_PROPERTY,
  PIE_EDITOR_PROPERTY_INNER
} from '@/views/chart/components/js/panel/charts/pie/common'
import type { Datum } from '@antv/g2plot/esm/types/common'
import { add } from 'mathjs'
import isEmpty from 'lodash-es/isEmpty'
import { cloneDeep } from 'lodash-es'
import { useI18n } from '@/hooks/web/useI18n'
import { G2 } from '@antv/g2plot'
import { createApp, h, ref } from 'vue'
import PiePresentationPanel from './PiePresentationPanel.vue'
import { normalizePieLayout } from '@/views/chart/components/editor/util/chartPalettes'
import {
  largePiePresentation,
  shortenPieName,
  pieColorMap,
  readablePieTooltip,
  PIE_CATEGORY_COLORS,
  preparePiePresentation
} from './piePresentation'
const { t } = useI18n()
const DEFAULT_DATA = []
// G2's spider layout does not accept the null items created by suppressed labels.
// Run layouts sequentially over visible labels, keeping every sector in the geometry.
G2.registerGeometryLabelLayout('de-pie-priority', async (items, labels, shapes, region, cfg) => {
  const visibleItems = items.filter(Boolean)
  const layouts = cfg?.compact
    ? ['hide-overlap', 'limit-in-canvas']
    : ['pie-spider', 'hide-overlap', 'limit-in-canvas']
  for (const type of layouts) {
    await G2.getGeometryLabelLayout(type)(visibleItems, labels, shapes, region)
  }
})
export class Pie extends G2PlotChartView<PieOptions, G2Pie> {
  axis: AxisType[] = PIE_AXIS_TYPE
  properties = PIE_EDITOR_PROPERTY
  propertyInner: EditorPropertyInner = {
    ...PIE_EDITOR_PROPERTY_INNER,
    'basic-style-selector': ['piePresentation', 'colors', 'alpha', 'radius', 'topN', 'seriesColor'],
    'tooltip-selector': [...PIE_EDITOR_PROPERTY_INNER['tooltip-selector'], 'carousel']
  }
  axisConfig = PIE_AXIS_CONFIG

  async drawChart(drawOptions: G2PlotDrawOptions<G2Pie>): Promise<G2Pie> {
    const { chart, container, action } = drawOptions
    this.configEmptyDataStyle(chart.data?.data, container, null, t('chart.no_data_or_not_positive'))
    chart.container = container
    if (!chart.data?.data?.length) {
      return
    }
    // data
    let data = cloneDeep(chart.data.data)
    // custom color
    const customAttr = parseJson(chart.customAttr)
    customAttr.basicStyle.pieLayout = normalizePieLayout(customAttr.basicStyle.pieLayout)
    chart.customAttr = customAttr
    const modern = ['large', 'ranking'].includes(customAttr.basicStyle.pieLayout)
    const presentation = modern
      ? preparePiePresentation(
          data,
          customAttr.basicStyle.calcTopN,
          customAttr.basicStyle.topN,
          customAttr.basicStyle.topNLabel
        )
      : null
    if (presentation) data = presentation.sectors
    const displayColors = pieColorMap(
      data,
      customAttr.basicStyle.colors,
      customAttr.basicStyle.seriesColor
    )
    const activeKey = ref<string | null>(null)
    const detailsOpen = ref(false)
    let panelApp: ReturnType<typeof createApp>
    let panelHost: HTMLElement
    let plotContainer = container
    const highlight = (key: string | null) => {
      activeKey.value = key
      newChart.setState('active', datum => datum.pieKey === key, true)
      newChart.setState('active', datum => datum.pieKey !== key, false)
      newChart.setState('inactive', datum => !!key && datum.pieKey !== key, true)
      newChart.setState('inactive', datum => !key || datum.pieKey === key, false)
    }
    const selectDatum = (datum: any, event?: MouseEvent) => {
      if (datum?.pieMembers) {
        detailsOpen.value = true
        return
      }
      const bounds = document.getElementById(container).getBoundingClientRect()
      detailsOpen.value = false
      action?.({
        data: { data: datum },
        x: event ? event.clientX - bounds.left : 0,
        y: event ? event.clientY - bounds.top : 0
      })
    }
    if (
      presentation &&
      (customAttr.basicStyle.pieLayout === 'ranking' || presentation.members.length)
    ) {
      plotContainer = `${container}-pie-plot`
      panelHost = document.createElement('div')
      panelHost.style.cssText = 'width:100%;height:100%;min-height:0'
      document.getElementById(container).appendChild(panelHost)
      panelApp = createApp({
        render: () =>
          h(PiePresentationPanel, {
            plotId: plotContainer,
            ranking: customAttr.basicStyle.pieLayout === 'ranking',
            compact: document.getElementById(container).clientWidth < 600,
            rows: [...presentation.sectors].sort((a, b) => Number(b.value) - Number(a.value)),
            categoryCount: presentation.rows.length,
            total: presentation.total,
            excluded: presentation.excluded,
            members: presentation.members,
            activeKey: activeKey.value,
            detailsOpen: detailsOpen.value,
            color: datum =>
              hexColorToRGBA(displayColors.get(datum.pieKey), customAttr.basicStyle.alpha),
            format: value =>
              String(valueFormatter(value, chart.yAxis?.[0]?.formatterCfg || formatterItem)),
            decimals: customAttr.label.reserveDecimalCount ?? 2,
            textColor: parseJson(chart.customStyle)?.legend?.color || '#475569',
            onHover: highlight,
            onSelect: selectDatum,
            onCloseDetails: () => {
              detailsOpen.value = false
            }
          })
      })
      panelApp.mount(panelHost)
    }
    const color = customAttr.basicStyle.colors.map(i =>
      hexColorToRGBA(i, customAttr.basicStyle.alpha)
    )
    // options
    const initOptions: PieOptions = {
      data: data,
      angleField: 'value',
      colorField: modern ? 'pieKey' : 'field',
      appendPadding: getPadding(chart),
      color,
      animation: false,
      pieStyle: {
        lineWidth: 0
      },
      statistic: {
        title: false,
        content: {
          style: {
            whiteSpace: 'pre-wrap',
            overflow: 'hidden',
            textOverflow: 'ellipsis'
          },
          content: ''
        }
      },
      interactions: [
        {
          type: 'legend-active',
          cfg: {
            start: [{ trigger: 'legend-item:mouseenter', action: ['element-active:reset'] }],
            end: [{ trigger: 'legend-item:mouseleave', action: ['element-active:reset'] }]
          }
        },
        {
          type: 'legend-filter',
          cfg: {
            start: [
              {
                trigger: 'legend-item:click',
                action: [
                  'list-unchecked:toggle',
                  'data-filter:filter',
                  'element-active:reset',
                  'element-highlight:reset'
                ]
              }
            ]
          }
        },
        {
          type: 'tooltip',
          cfg: {
            start: [{ trigger: 'interval:mousemove', action: 'tooltip:show' }],
            end: [{ trigger: 'interval:mouseleave', action: 'tooltip:hide' }]
          }
        },
        {
          type: 'active-region',
          cfg: {
            start: [{ trigger: 'interval:mousemove', action: 'active-region:show' }],
            end: [{ trigger: 'interval:mouseleave', action: 'active-region:hide' }]
          }
        }
      ],
      meta: {
        [modern ? 'pieKey' : 'field']: {
          type: 'cat'
        }
      },
      state: {
        active: {
          style: {
            lineWidth: 2,
            fillOpacity: modern ? 1 : 0.5
          }
        },
        inactive: { style: { fillOpacity: 0.25 } }
      }
    }
    const options = this.setupOptions(chart, initOptions)
    const { Pie: G2Pie } = await import('@antv/g2plot/esm/plots/pie')
    const newChart = new G2Pie(plotContainer, options)
    newChart.on('interval:click', d => {
      if (d.data?.data?.pieMembers) {
        detailsOpen.value = true
        return
      }
      if (!modern && customAttr.basicStyle.calcTopN && d.data?.data?.others) {
        return
      }
      action?.(d)
    })
    newChart.on('interval:mouseenter', event => highlight(event.data?.data?.pieKey || null))
    newChart.on('interval:mouseleave', () => highlight(null))
    if (panelApp) {
      const destroy = newChart.destroy.bind(newChart)
      newChart.destroy = () => {
        try {
          destroy()
        } finally {
          panelApp.unmount()
          panelHost.remove()
        }
      }
    }
    configPlotTooltipEvent(chart, newChart)
    return newChart
  }

  protected configLabel(chart: Chart, options: PieOptions): PieOptions {
    const { label: labelAttr, basicStyle } = parseJson(chart.customAttr)
    if (basicStyle.pieLayout === 'ranking') return { ...options, label: false }
    if (!labelAttr?.show) {
      return {
        ...options,
        label: false
      }
    }
    if (basicStyle.pieLayout === 'large') {
      const container = document.getElementById(chart.container)
      const presentation = largePiePresentation(
        options.data,
        container?.clientWidth || 600,
        container?.clientHeight || 400,
        labelAttr.fontSize,
        basicStyle.pieLabelLimit
      )
      return {
        ...options,
        label: {
          type: presentation.compact ? 'inner' : 'no',
          offset: presentation.compact ? -20 : 12,
          layout: [{ type: 'de-pie-priority', cfg: { compact: presentation.compact } }],
          labelLine: presentation.compact
            ? false
            : { style: { stroke: hexColorToRGBA(labelAttr.color || '#64748b', 40), lineWidth: 1 } },
          style: { fill: labelAttr.color, fontSize: presentation.fontSize },
          formatter: (datum: Datum) => {
            if (!presentation.showLabel(datum)) return ''
            const percent = `${(Number(datum.percent || 0) * 100).toFixed(
              labelAttr.reserveDecimalCount ?? 2
            )}%`
            if (presentation.compact) return percent
            const content = []
            if (labelAttr.showDimension)
              content.push(
                shortenPieName(
                  datum.pieMembers
                    ? `${datum.field}（${datum.pieMembers.length} 类）`
                    : datum.field,
                  presentation.labelWidth,
                  presentation.fontSize
                )
              )
            if (labelAttr.showQuota)
              content.push(valueFormatter(datum.value, labelAttr.quotaLabelFormatter))
            if (labelAttr.showProportion) content.push(percent)
            return content.join(' ')
          }
        }
      }
    }
    const layout = []
    let textAlign = undefined
    if (labelAttr.position === 'inner') {
      textAlign = 'center'
      if (labelAttr.fullDisplay) {
        layout.push({ type: 'limit-in-plot' })
      } else {
        layout.push({ type: 'limit-in-canvas' })
        layout.push({ type: 'hide-overlap' })
      }
    } else {
      if (!labelAttr.fullDisplay) {
        layout.push({ type: 'limit-in-plot' })
      }
    }
    let labelType = labelAttr.position === 'outer' ? 'spider' : labelAttr.position
    if (layout.length === 0) {
      labelType = 'no'
    }
    const label = {
      type: labelType,
      textAlign,
      layout,
      autoRotate: false,
      style: {
        fill: labelAttr.color,
        fontSize: labelAttr.fontSize
      },
      formatter: (param: Datum) => {
        let res = param.value
        const contentItems = []
        if (labelAttr.showDimension) {
          contentItems.push(param.field)
        }
        if (labelAttr.showQuota) {
          contentItems.push(valueFormatter(param.value, labelAttr.quotaLabelFormatter))
        }
        if (labelAttr.showProportion) {
          const percentage = `${(Math.round(param.percent * 10000) / 100).toFixed(
            labelAttr.reserveDecimalCount
          )}%`
          if (labelAttr.showDimension && labelAttr.showQuota) {
            contentItems.push(`(${percentage})`)
          } else {
            contentItems.push(percentage)
          }
        }
        res = contentItems.join(' ')
        return res
      }
    }
    return { ...options, label }
  }

  protected configTooltip(chart: Chart, options: PieOptions): PieOptions {
    if (parseJson(chart.customAttr).tooltip.contentMode === 'explicit') {
      return {
        ...options,
        tooltip: readablePieTooltip(
          getExplicitChartTooltip(chart),
          options.data,
          parseJson(chart.customAttr).tooltip.fontSize
        )
      }
    }
    const { tooltip: tooltipAttr, label } = parseJson(chart.customAttr)
    const { yAxis } = chart
    if (!tooltipAttr.show) {
      return {
        ...options,
        tooltip: false
      }
    }
    const reserveDecimalCount = label.reserveDecimalCount
    const seriesTotalMap = getTooltipSeriesTotalMap(options.data)
    // trick, cal total, maybe use scale of chart in plot instance
    const total = options.data?.reduce((pre, next) => add(pre, next.value ?? 0), 0)

    const tooltip: PieOptions['tooltip'] = {
      showTitle: true,
      title: () => undefined,
      customItems(originalItems) {
        let tooltipItems = originalItems
        if (tooltipAttr.seriesTooltipFormatter?.length) {
          tooltipItems = originalItems.filter(item =>
            visibleTooltipField(tooltipAttr.seriesTooltipFormatter, item.data.quotaList[0])
          )
        }
        const result = []
        const head = originalItems[0]
        tooltipItems.forEach(item => {
          const formatter =
            visibleTooltipField(tooltipAttr.seriesTooltipFormatter, item.data.quotaList[0]) ??
            yAxis[0]
          const originValue = parseFloat(item.value as string)
          const value = valueFormatter(originValue, formatter.formatterCfg)
          // sync with label
          const percent = (Math.round((originValue / total) * 10000) / 100).toFixed(
            reserveDecimalCount
          )
          const name = isEmpty(formatter.chartShowName) ? formatter.name : formatter.chartShowName
          result.push({ ...item, name, value: `${value ?? ''} (${percent}%)` })
        })
        head?.data?.dynamicTooltipValue?.forEach(item => {
          const formatter = visibleTooltipField(tooltipAttr.seriesTooltipFormatter, item)
          if (formatter) {
            const total = seriesTotalMap[item.fieldId]
            // sync with label
            const percent = (Math.round((item.value / total) * 10000) / 100).toFixed(
              reserveDecimalCount
            )
            const value = valueFormatter(parseFloat(item.value), formatter.formatterCfg)
            const name = isEmpty(formatter.chartShowName) ? formatter.name : formatter.chartShowName
            result.push({ color: 'grey', name, value: `${value ?? ''} (${percent}%)` })
          }
        })
        return result
      },
      container: getTooltipContainer(`tooltip-${chart.id}`, chart.container),
      itemTpl: TOOLTIP_TPL,
      shared: true,
      enterable: true
    }
    return {
      ...options,
      tooltip: readablePieTooltip(tooltip, options.data, tooltipAttr.fontSize)
    }
  }

  protected configBasicStyle(chart: Chart, options: PieOptions): PieOptions {
    const customAttr = parseJson(chart.customAttr)
    const { basicStyle } = customAttr
    const { data } = options
    if (
      !['large', 'ranking'].includes(basicStyle.pieLayout) &&
      data?.length &&
      basicStyle.calcTopN &&
      data.length > basicStyle.topN
    ) {
      data.sort((a, b) => b.value - a.value)
      const otherItems = data.splice(basicStyle.topN)
      const initOtherItem = {
        ...data[0],
        dynamicTooltipValue: [],
        field: basicStyle.topNLabel,
        name: basicStyle.topNLabel,
        value: 0,
        others: true
      }
      const dynamicTotalMap: Record<string, number> = {}
      otherItems.reduce((p, n) => {
        p.value += n.value ?? 0
        n.dynamicTooltipValue?.forEach(val => {
          dynamicTotalMap[val.fieldId] = (dynamicTotalMap[val.fieldId] || 0) + val.value
        })
        return p
      }, initOtherItem)
      for (const key in dynamicTotalMap) {
        initOtherItem.dynamicTooltipValue.push({
          fieldId: key,
          value: dynamicTotalMap[key]
        })
      }
      data.push(initOtherItem)
    }
    if (basicStyle.pieLayout === 'ranking')
      return {
        ...options,
        padding: 12,
        appendPadding: 0,
        radius: 0.94,
        innerRadius: 0.66,
        statistic: { title: false, content: false },
        legend: false,
        pieStyle: { lineWidth: 1, stroke: hexColorToRGBA(customAttr.label?.color || '#64748b', 15) }
      }
    if (basicStyle.pieLayout === 'large') {
      const container = document.getElementById(chart.container)
      const presentation = largePiePresentation(
        data,
        container?.clientWidth || 600,
        container?.clientHeight || 400,
        customAttr.label?.fontSize,
        basicStyle.pieLabelLimit
      )
      return {
        ...options,
        data,
        padding: presentation.padding,
        appendPadding: 0,
        radius: presentation.radius,
        pieStyle: {
          lineWidth: 1,
          stroke: hexColorToRGBA(customAttr.label?.color || '#64748b', 15)
        }
      }
    }
    return {
      ...options,
      radius: basicStyle.radius / 100
    }
  }
  protected configSingleDimensionColor(chart: Chart, options: PieOptions): PieOptions {
    const { basicStyle } = parseJson(chart.customAttr)
    if (!['large', 'ranking'].includes(basicStyle.pieLayout))
      return super.configSingleDimensionColor(chart, options)
    const colors = basicStyle.colors || PIE_CATEGORY_COLORS
    const colorsByKey = pieColorMap(options.data, colors, basicStyle.seriesColor)
    return {
      ...options,
      color: (datum: Datum) => {
        return hexColorToRGBA(colorsByKey.get(datum.pieKey) || colors[0], basicStyle.alpha)
      }
    }
  }
  setupDefaultOptions(chart: ChartObj): ChartObj {
    const { customAttr, customStyle } = chart
    customAttr.basicStyle.pieLayout = normalizePieLayout(customAttr.basicStyle.pieLayout)
    const { label } = customAttr
    if (!['inner', 'outer'].includes(label.position)) {
      label.position = 'outer'
    }
    customAttr.label = {
      ...label,
      show: true,
      showDimension: true,
      showProportion: true,
      reserveDecimalCount: 2
    }
    const { legend } = customStyle
    legend.show = false
    return chart
  }

  public setupSeriesColor(chart: ChartObj, data?: any[]): ChartBasicStyle['seriesColor'] {
    data = cloneDeep(data)
    const { calcTopN, topN, topNLabel } = chart.customAttr.basicStyle
    if (data?.length && calcTopN && data.length > topN) {
      data.sort((a, b) => b.value - a.value)
      data.splice(topN)
      data.push({
        field: topNLabel,
        value: 0
      })
    }
    return setUpSingleDimensionSeriesColor(chart, data)
  }

  protected setupOptions(chart: Chart, options: PieOptions): PieOptions {
    return flow(
      this.configTheme,
      this.configBasicStyle,
      this.configSingleDimensionColor,
      this.configLabel,
      this.configTooltip,
      this.configLegend
    )(chart, options, {}, this)
  }

  protected configLegend(chart: Chart, options: PieOptions): PieOptions {
    if (['large', 'ranking'].includes(parseJson(chart.customAttr).basicStyle.pieLayout))
      return { ...options, legend: false }
    return super.configLegend(chart, options)
  }

  constructor(name = 'pie') {
    super(name, DEFAULT_DATA)
  }
}

export class PieDonut extends Pie {
  propertyInner: EditorPropertyInner = {
    ...PIE_EDITOR_PROPERTY_INNER,
    'basic-style-selector': [
      'piePresentation',
      'colors',
      'alpha',
      'radius',
      'innerRadius',
      'topN',
      'seriesColor'
    ],
    'tooltip-selector': [...PIE_EDITOR_PROPERTY_INNER['tooltip-selector'], 'carousel']
  }
  protected configBasicStyle(chart: Chart, options: PieOptions): PieOptions {
    const tmp = super.configBasicStyle(chart, options)
    const { basicStyle } = parseJson(chart.customAttr)
    if (basicStyle.pieLayout === 'large') return { ...tmp, innerRadius: 0 }
    if (basicStyle.pieLayout === 'ranking') return tmp
    return {
      ...tmp,
      radius: basicStyle.radius / 100,
      innerRadius: basicStyle.innerRadius / 100
    }
  }

  constructor() {
    super('pie-donut')
  }
}
