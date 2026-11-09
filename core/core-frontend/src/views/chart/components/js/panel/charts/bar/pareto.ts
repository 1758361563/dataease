import type { DualAxes, DualAxesOptions } from '@antv/g2plot/esm/plots/dual-axes'
import { G2PlotChartView, G2PlotDrawOptions } from '../../types/impl/g2plot'
import { getPadding, getYAxis, configPlotTooltipEvent } from '../../common/common_antv'
import { parseJson, hexColorToRGBA } from '../../../util'
import { valueFormatter } from '../../../formatter'
import { BAR_EDITOR_PROPERTY_INNER } from './common'
import { paretoData } from './pareto-data'
import { findTooltipField } from '../../../tooltipFieldIdentity'

export class Pareto extends G2PlotChartView<DualAxesOptions, DualAxes> {
  properties: EditorProperty[] = [
    'background-overall-component',
    'border-style',
    'basic-style-selector',
    'title-selector',
    'legend-selector',
    'x-axis-selector',
    'y-axis-selector',
    'jump-set',
    'linkage'
  ]
  propertyInner: EditorPropertyInner = {
    ...BAR_EDITOR_PROPERTY_INNER,
    'basic-style-selector': ['colors', 'alpha', 'columnWidthRatio'],
    'y-axis-selector': [
      'name',
      'color',
      'fontSize',
      'axisLine',
      'splitLine',
      'axisLabel',
      'axisLabelFormatter'
    ]
  }
  axis: AxisType[] = ['xAxis', 'yAxis', 'filter', 'extTooltip']
  axisConfig = {
    xAxis: { name: '类别 / 维度', type: 'd' as const, limit: 1 },
    yAxis: { name: '数值 / 指标', type: 'q' as const, limit: 1 }
  }
  constructor() {
    super('pareto', [])
  }
  protected setupOptions(chart: Chart, options: DualAxesOptions): DualAxesOptions {
    return this.configXAxis(chart, this.configLegend(chart, this.configTheme(chart, options)))
  }
  async drawChart({ chart, container, action }: G2PlotDrawOptions<DualAxes>): Promise<DualAxes> {
    if (!chart.data?.data) return
    const rows = paretoData(chart.data.data)
    const attr = parseJson(chart.customAttr)
    const style = attr.basicStyle || {}
    const barColor = hexColorToRGBA(style.colors?.[0] || '#5babb0', style.alpha ?? 100)
    const lineColor = style.paretoLineColor || '#e6a23c'
    const threshold = Number.isFinite(Number(style.paretoThreshold))
      ? Math.min(100, Math.max(0, Number(style.paretoThreshold)))
      : 80
    const name = chart.yAxis?.[0]?.chartShowName || chart.yAxis?.[0]?.name || '数值'
    const format = (value: number) =>
      chart.yAxis?.[0]?.formatterCfg
        ? valueFormatter(value, chart.yAxis[0].formatterCfg)
        : String(value)
    const percent = (value: number) => (value == null ? '—' : `${(value * 100).toFixed(1)}%`)
    let options: DualAxesOptions = {
      data: [rows, rows],
      xField: 'field',
      yField: ['value', 'cumulative'],
      appendPadding: getPadding(chart),
      meta: {
        field: { type: 'cat', values: rows.map(row => row.field) },
        value: { alias: name },
        cumulative: { alias: '累计占比', min: 0, max: 1 }
      },
      geometryOptions: [
        {
          geometry: 'column',
          color: barColor,
          columnWidthRatio: Math.min(1, Math.max(0.01, (style.columnWidthRatio ?? 60) / 100)),
          label: style.paretoValueLabels ? { formatter: datum => format(datum.value) } : false
        },
        {
          geometry: 'line',
          color: lineColor,
          lineStyle: { lineWidth: 2 },
          point: { size: 3 },
          label: style.paretoPercentLabels
            ? { formatter: datum => percent(datum.cumulative) }
            : false
        }
      ],
      tooltip: {
        shared: true,
        showMarkers: true,
        formatter: datum =>
          datum.cumulative !== undefined && datum.value === undefined
            ? { name: '累计占比', value: percent(datum.cumulative) }
            : { name, value: format(datum.value) },
        customItems: items => {
          const extra = []
          for (const item of items[0]?.data?.dynamicTooltipValue || []) {
            const field = findTooltipField(chart.extTooltip || [], item)
            if (!field) continue
            extra.push({
              color: '#8996aa',
              name: field.chartShowName || field.name,
              value:
                item.value == null
                  ? item.stringValue ?? '—'
                  : field.formatterCfg
                  ? valueFormatter(Number(item.value), field.formatterCfg)
                  : String(item.value)
            })
          }
          return [...items, ...extra]
        }
      },
      animation: false
    }
    options = this.setupOptions(chart, options)
    const y = getYAxis(chart)
    options = {
      ...options,
      yAxis: {
        value: y === false ? false : { ...y, min: 0 },
        cumulative: {
          min: 0,
          max: 1,
          tickCount: 6,
          grid: null,
          label: { formatter: value => `${Math.round(Number(value) * 100)}%` }
        }
      }
    }
    options = {
      ...options,
      annotations: {
        cumulative:
          style.paretoThresholdShow === false
            ? []
            : [
                {
                  type: 'line',
                  start: ['min', threshold / 100],
                  end: ['max', threshold / 100],
                  style: { stroke: '#8a9dbc', lineDash: [4, 4] },
                  text: { content: `${threshold}%`, position: 'end', style: { fill: '#8a9dbc' } }
                }
              ]
      }
    }
    // Separate value objects keep the dual-axis tooltip formatter unambiguous.
    options = {
      ...options,
      data: [
        rows.map(row => ({ ...row, cumulative: undefined })),
        rows.map(row => ({ ...row, value: undefined }))
      ]
    }
    const { DualAxes } = await import('@antv/g2plot/esm/plots/dual-axes')
    const plot = new DualAxes(container, options)
    plot.on('interval:click', action)
    plot.on('point:click', action)
    configPlotTooltipEvent(chart, plot as unknown as Parameters<typeof configPlotTooltipEvent>[1])
    return plot
  }
}
