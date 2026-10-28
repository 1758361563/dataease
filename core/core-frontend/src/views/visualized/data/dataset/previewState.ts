import type { PreviewRule } from '@/api/dataset'

// A renamed display label is the dataset alias; identical names need no second line.
export const fieldOriginalName = (field: { name?: string; originName?: string }) => {
  const original = field.originName || ''
  return field.name && field.name.trim() !== original.trim() ? original : ''
}

export const matchesField = (field, search: string) => {
  const needle = search.trim().toLocaleLowerCase()
  return (
    !needle ||
    [field.name, field.originName].some(value =>
      String(value || '')
        .toLocaleLowerCase()
        .includes(needle)
    )
  )
}

export const pasteValues = (text: string) => [
  ...new Set(text.split(/\r?\n/).filter(value => value !== ''))
]

export const validateRules = (rules: PreviewRule[], fields: any[]) => {
  if (rules.length > 100) return '最多支持100条筛选规则'
  for (const rule of rules) {
    const field = fields.find(item => String(item.id) === rule.fieldId)
    if (!field) return '筛选字段已失效，请重新选择'
    if (rule.deType !== undefined && field.deType !== rule.deType)
      return '筛选字段类型已改变，请重新配置规则'
    if (['null', 'not_null', 'empty', 'not_empty'].includes(rule.operator)) continue
    if (
      !rule.values.length ||
      rule.values.some(value => value == null) ||
      (!['in', 'not in'].includes(rule.operator) && rule.values.some(value => value === ''))
    )
      return '请填写完整的筛选值'
    if (rule.values.length > 1000) return '每条规则最多1000个值'
    if (
      [2, 3, 4].includes(field.deType) &&
      rule.values.some(value => !/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:e[+-]?\d+)?$/i.test(value))
    )
      return '数值字段请填写有效数字'
    if (rule.operator === 'between') {
      if (rule.values.length !== 2) return '范围需要起止两个值'
      const numeric = [2, 3, 4].includes(field.deType)
      if (
        numeric ? Number(rule.values[0]) > Number(rule.values[1]) : rule.values[0] > rule.values[1]
      )
        return '范围起始值不能大于结束值'
    }
  }
  return ''
}
