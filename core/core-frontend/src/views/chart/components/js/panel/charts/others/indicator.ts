import { AbstractChartView, ChartLibraryType, ChartRenderType } from '../../types'
import { useI18n } from '@/hooks/web/useI18n'
import { COLOR_CASES } from '@/views/chart/components/editor/util/chart'

const { t } = useI18n()
/**
 * 指标卡图表
 */
export class IndicatorChartView extends AbstractChartView {
  selectorSpec: EditorSelectorSpec
  properties: EditorProperty[] = [
    'background-overall-component',
    'border-style',
    'title-selector',
    'indicator-value-selector',
    'indicator-name-selector',
    'threshold',
    'function-cfg',
    'linkage',
    'jump-set'
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
    'function-cfg': ['emptyDataStrategy']
  }
  axis: AxisType[] = ['yAxis', 'filter']
  axisConfig: AxisConfig = {
    yAxis: {
      name: `${t('chart.quota')}`,
      limit: 1
    }
  }
  setupDefaultOptions(chart: ChartObj): ChartObj {
    const basicColors = COLOR_CASES[0].colors
    chart.customAttr.basicStyle.colors = basicColors
    // 与对比指标卡同一套视觉默认：主题主色数值 + 中性灰名称；名称在上
    chart.customAttr.indicator.color = basicColors[0]
    chart.customAttr.indicator.fontSize = 40
    chart.customAttr.indicator.isBolder = true
    chart.customAttr.indicator.suffixEnable = false
    chart.customAttr.indicatorName.color = '#4a5160'
    chart.customAttr.indicatorName.fontSize = 18
    chart.customAttr.indicatorName.isBolder = true
    chart.customAttr.indicatorName.show = true
    chart.customAttr.indicatorName.namePosition = 'top'
    // 默认不显示图表类型标题（左上角「指标卡」）
    if (chart.customStyle?.text) {
      chart.customStyle.text.show = false
    }
    return chart
  }

  constructor() {
    super(ChartRenderType.CUSTOM, ChartLibraryType.INDICATOR, 'indicator')
  }
}
