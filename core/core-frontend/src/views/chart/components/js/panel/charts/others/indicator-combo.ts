import { AbstractChartView, ChartLibraryType, ChartRenderType } from '../../types'
import { useI18n } from '@/hooks/web/useI18n'
import { COLOR_CASES } from '@/views/chart/components/editor/util/chart'
import { DEFAULT_INDICATOR_COMBO_LAYOUT } from '@/custom/company-bi/components/indicator-combo/defaults'

const { t } = useI18n()
/**
 * 对比指标卡（company）
 *
 * 内部：yAxis[0] 中央主展示，yAxis[1..] 底部对比（已配置 compareCalc 的才渲染）。
 * 取数：使用 indicator-combo 后端处理器，允许一个拆分维度参与 GROUP BY。
 */
export class IndicatorComboChartView extends AbstractChartView {
  selectorSpec: EditorSelectorSpec = {
    'indicator-combo-layout-selector': {
      title: `${t('chart.indicator_combo_layout')}`
    }
  }
  // v1 暂不提供 linkage/jump-set
  properties: EditorProperty[] = [
    'background-overall-component',
    'border-style',
    'title-selector',
    'indicator-value-selector',
    'indicator-name-selector',
    'indicator-combo-layout-selector',
    'threshold',
    'function-cfg'
  ]
  propertyInner: EditorPropertyInner = {
    'background-overall-component': ['all'],
    'border-style': ['all'],
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
    ],
    'indicator-value-selector': [
      'fontSize',
      'color',
      'hPosition',
      'isItalic',
      'isBolder',
      'fontFamily',
      'letterSpace',
      'fontShadow'
    ],
    'indicator-name-selector': [
      'title',
      'fontSize',
      'color',
      'hPosition',
      'isItalic',
      'isBolder',
      'fontFamily',
      'letterSpace',
      'fontShadow'
    ],
    'indicator-combo-layout-selector': ['all'],
    'function-cfg': ['emptyDataStrategy']
  }
  axis: AxisType[] = ['xAxis', 'yAxis', 'filter']
  axisConfig: AxisConfig = {
    xAxis: {
      name: `${t('chart.indicator_combo_breakdown')} / ${t('chart.dimension')}`,
      type: 'd',
      min: 0,
      limit: 1,
      allowEmpty: true
    },
    yAxis: {
      name: `${t('chart.quota')}`,
      min: 1,
      limit: 3,
      // 允许同一数据集字段重复拖入；实例身份靠 metricInstanceId
      duplicate: true
    }
  }
  setupDefaultOptions(chart: ChartObj): ChartObj {
    const basicColors = COLOR_CASES[0].colors
    chart.customAttr.basicStyle.colors = basicColors
    // 与单指标卡同一套视觉默认
    chart.customAttr.indicator.color = basicColors[0]
    chart.customAttr.indicator.fontSize = 40
    chart.customAttr.indicator.isBolder = true
    chart.customAttr.indicatorName.color = '#4a5160'
    chart.customAttr.indicatorName.fontSize = 18
    chart.customAttr.indicatorName.show = true
    chart.customAttr.indicatorName.namePosition = 'top'
    chart.customAttr.indicatorName.isBolder = true
    chart.customAttr.indicator.suffixEnable = false
    if (chart.customStyle?.text) {
      chart.customStyle.text.show = false
    }
    chart.customAttr.indicatorCombo = {
      ...DEFAULT_INDICATOR_COMBO_LAYOUT
    }
    return chart
  }

  constructor() {
    super(ChartRenderType.CUSTOM, ChartLibraryType.INDICATOR_COMBO, 'indicator-combo')
  }
}
