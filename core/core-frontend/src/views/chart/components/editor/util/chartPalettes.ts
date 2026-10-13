export const CHART_PALETTES = [
  {
    name: '均衡分类色',
    value: 'default',
    colors: ['#4878D6', '#86C76F', '#3BB9C6', '#F1AA4C', '#927AD4', '#E57F94', '#4DA58A', '#E5C55B']
  },
  {
    name: '柔和多色',
    value: 'guanyuan_soft',
    colors: ['#4E76CC', '#9EB6EB', '#F2D93B', '#F58B18', '#7BB6E8', '#A692DB', '#72C9D5', '#E8B46B']
  },
  {
    name: '低饱和',
    value: 'morandi',
    colors: ['#718DA6', '#A4BC93', '#D0A17C', '#91B7B5', '#B5A1C1', '#D3AC8E', '#8FA79E', '#BA9CA5']
  }
]

export const normalizePieLayout = (layout?: string): 'large' | 'ranking' =>
  layout === 'ranking' ? 'ranking' : 'large'
