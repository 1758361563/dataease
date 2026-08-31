import { getCSSVariable } from '@/utils/color'

/**
 * 轴上字段标签颜色 / 有效性。
 *
 * Dataset field groupType = default role（只决定字段在左侧哪一栏）
 * Chart placement groupType = actual role（决定标签颜色）
 *
 * 红色只表示物理字段缺失或 deType 不一致，
 * 不再表示 Dataset default role 与 Chart placement role 不一致。
 */
export function getItemType(dimensionData, quotaData, item) {
  // Check whether the current view is in template status
  // ( dimensionData and quotaData have no data). If yes, return 'success' directly
  if (dimensionData.length === 0 && quotaData.length === 0) {
    return item?.groupType === 'q' ? '#04b49c' : getCSSVariable()
  }

  const source = [...(dimensionData || []), ...(quotaData || [])]
  const checked = source.some(ele => physicalFieldMatches(ele, item))

  if (!checked) {
    return '#F54A45'
  }
  if (item.groupType === 'q') {
    return '#04b49c'
  }
  return getCSSVariable()
}

function physicalFieldMatches(ele, item): boolean {
  if (!ele || !item) {
    return false
  }
  if (ele.deType !== item.deType) {
    return false
  }
  if (item.chartId) {
    return ele.originName === item.originName && ele.dataeaseName === item.dataeaseName
  }
  return ele.id === item.id && ele.originName === item.originName
}

export function getOriginFieldName(dimensionList, quotaList, field) {
  let originName = ''
  for (let i = 0; i < dimensionList.length; i++) {
    const item = dimensionList[i]
    if (item.id === field.id) {
      originName = item.name
      break
    }
  }
  for (let i = 0; i < quotaList.length; i++) {
    const item = quotaList[i]
    if (item.id === field.id) {
      originName = item.name
      break
    }
  }
  return originName
}

export function resetValueFormatter(item) {
  if (item) {
    item.formatterCfg = {
      type: 'auto', // auto,value,percent
      unit: 1, // 换算单位
      suffix: '', // 单位后缀
      decimalCount: 2, // 小数位数
      thousandSeparator: true // 千分符
    }
  }
}
