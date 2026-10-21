import { TreemapOptions, Treemap as G2Treemap } from '@antv/g2plot/esm/plots/treemap'
import { G2PlotChartView, G2PlotDrawOptions } from '../../types/impl/g2plot'
import { flow, parseJson, setUpSingleDimensionSeriesColor } from '../../../util'
import { getPadding } from '../../common/common_antv'
import { valueFormatter } from '../../../formatter'
import { Label } from '@antv/g2plot/lib/types/label'
import { Datum } from '@antv/g2plot/esm/types/common'
import { useI18n } from '@/hooks/web/useI18n'
import cloneDeep from 'lodash-es/cloneDeep'
import {
  highlightTreemap,
  treemapDatum,
  treemapPercent,
  treemapTooltip
} from './treemapPresentation'

const { t } = useI18n()

/**
 * 矩形树图
 */
export class Treemap extends G2PlotChartView<TreemapOptions, G2Treemap> {
  properties: EditorProperty[] = [
    'background-overall-component',
    'border-style',
    'basic-style-selector',
    'title-selector',
    'legend-selector',
    'label-selector',
    'tooltip-selector',
    'jump-set',
    'linkage'
  ]
  propertyInner: EditorPropertyInner = {
    'background-overall-component': ['all'],
    'border-style': ['all'],
    'basic-style-selector': ['colors', 'alpha', 'seriesColor'],
    'label-selector': ['fontSize', 'color', 'showDimension', 'showQuota', 'showProportion'],
    'legend-selector': ['icon', 'orient', 'fontSize', 'color', 'hPosition', 'vPosition'],
    'tooltip-selector': [
      'fontSize',
      'color',
      'backgroundColor',
      'showProportion',
      'seriesTooltipFormatter',
      'show'
    ],
    'title-selector': [
      'title',
      'fontSize',
      'color',
      'hPosition',
      'isItalic',
      'isBolder',
      'remarkShow',
      'fontFamily',
      'letterSpace',
      'fontShadow'
    ]
  }
  axis: AxisType[] = ['xAxis', 'yAxis', 'filter', 'drill', 'extLabel', 'extTooltip']
  axisConfig: AxisConfig = {
    xAxis: {
      name: `${t('chart.drag_block_treemap_label')} / ${t('chart.dimension')}`,
      type: 'd'
    },
    yAxis: {
      name: `${t('chart.drag_block_treemap_size')} / ${t('chart.quota')}`,
      limit: 1
    }
  }

  async drawChart(drawOptions: G2PlotDrawOptions<G2Treemap>): Promise<G2Treemap> {
    const { chart, container, action } = drawOptions
    if (!chart.data?.data?.length) {
      return
    }
    const data = cloneDeep(chart.data.data).map((datum, index) => ({
      ...datum,
      treemapKey: `node:${index}`
    }))
    const baseOptions = {
      data: {
        name: 'root',
        children: data
      },
      colorField: 'name',
      rawFields: [
        'value',
        'field',
        'name',
        'treemapKey',
        'quotaList',
        'dimensionList',
        'dynamicTooltipValue',
        'path'
      ],
      state: {
        active: { style: { lineWidth: 2, fillOpacity: 1 } },
        inactive: { style: { fillOpacity: 0.25 } }
      },
      appendPadding: getPadding(chart),
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
            start: [{ trigger: 'element:mousemove', action: 'tooltip:show' }],
            end: [{ trigger: 'element:mouseleave', action: 'tooltip:hide' }]
          }
        }
      ]
    }
    const options = this.setupOptions(chart, baseOptions)
    const { Treemap: G2Treemap } = await import('@antv/g2plot/esm/plots/treemap')
    const newChart = new G2Treemap(container, options)
    newChart.on('polygon:click', action)
    newChart.on('element:mouseenter', event =>
      highlightTreemap(newChart, treemapDatum(event.data?.data).treemapKey ?? null)
    )
    newChart.on('element:mouseleave', () => highlightTreemap(newChart, null))
    newChart.on('plot:leave', () => highlightTreemap(newChart, null))
    return newChart
  }
  protected configTooltip(chart: Chart, options: TreemapOptions): TreemapOptions {
    const total = (options.data.children || []).reduce(
      (sum, datum) => sum + (Number(datum.value) || 0),
      0
    )
    return {
      ...options,
      tooltip: treemapTooltip(
        { ...chart, customAttr: parseJson(chart.customAttr) },
        total,
        valueFormatter
      )
    }
  }
  protected configLabel(chart: Chart, options: TreemapOptions): TreemapOptions {
    const customAttr: DeepPartial<ChartAttr> = parseJson(chart.customAttr)
    const labelAttr = customAttr.label
    if (!labelAttr.show) {
      return {
        ...options,
        label: false
      }
    }
    const label: Label = {
      style: {
        fill: labelAttr.color,
        fontSize: labelAttr.fontSize
      },
      formatter: function (param: Datum) {
        let res = param.value
        const contentItems = []
        if (labelAttr.showDimension) {
          contentItems.push(param.field)
        }
        if (labelAttr.showQuota) {
          contentItems.push(valueFormatter(param.value, labelAttr.quotaLabelFormatter))
        }
        if (labelAttr.showProportion) {
          const percentage = treemapPercent(
            param.value,
            param.parent?.value,
            labelAttr.reserveDecimalCount
          )
          contentItems.push(percentage)
        }
        res = contentItems.join('\n')
        return res
      }
    }
    if (labelAttr.fullDisplay) {
      label.layout = [{ type: 'limit-in-plot' }]
    }
    return { ...options, label }
  }

  setupDefaultOptions(chart: ChartObj): ChartObj {
    const { customAttr, customStyle } = chart
    const { label } = customAttr
    customAttr.label = {
      ...label,
      show: true,
      showDimension: true,
      showProportion: true,
      reserveDecimalCount: 2
    }
    customAttr.tooltip.showProportion ??= true
    customAttr.tooltip.reserveDecimalCount ??= 2
    const { legend } = customStyle
    legend.show = false
    return chart
  }
  public setupSeriesColor(chart: ChartObj, data?: any[]): ChartBasicStyle['seriesColor'] {
    return setUpSingleDimensionSeriesColor(
      chart,
      data ? [...data].sort((a, b) => b.value - a.value) : data
    )
  }
  protected configColor(chart: Chart, options: TreemapOptions): TreemapOptions {
    const data = [...options.data.children].sort((a, b) => b.value - a.value)
    const tmpOptions = this.configSingleDimensionColor(chart, { ...options, data })
    return { ...options, data: { ...options.data, children: data }, color: tmpOptions.color }
  }
  protected setupOptions(chart: Chart, options: TreemapOptions): TreemapOptions {
    return flow(
      this.configTheme,
      this.configColor,
      this.configLabel,
      this.configTooltip,
      this.configLegend
    )(chart, options, {}, this)
  }

  constructor() {
    super('treemap', [])
  }
}
