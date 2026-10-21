export function parseDashboardMigration(text: string) {
  const pack = JSON.parse(text)
  if (pack.dvType !== 'dashboard' || ![2, 3].includes(pack.version)) {
    throw new Error('请选择版本 2 或 3 的仪表板应用模板')
  }
  if (
    !Array.isArray(JSON.parse(pack.componentData)) ||
    !isObject(JSON.parse(pack.canvasStyleData)) ||
    !isObject(JSON.parse(pack.dynamicData))
  ) {
    throw new Error('模板缺少有效的画布或图表配置')
  }
  const app = pack.appData ? JSON.parse(pack.appData) : null
  if (
    app &&
    (!Array.isArray(app.datasetGroupsInfo) ||
      !Array.isArray(app.datasetTablesInfo) ||
      !Array.isArray(app.datasetTableFieldsInfo) ||
      !app.visualizationInfo)
  ) {
    throw new Error('应用模板缺少数据集依赖信息')
  }
  // Old packages can contain credentials and cached query results: discard before uploading.
  if (app) {
    app.datasourceInfo?.forEach(source => {
      source.configuration = null
    })
    app.datasourceTaskInfo = []
    pack.appData = JSON.stringify(app)
  }
  const charts = JSON.parse(pack.dynamicData)
  Object.values(charts).forEach((chart: any) => {
    delete chart.data
  })
  pack.dynamicData = JSON.stringify(charts)
  pack.snapshot = ''
  return { pack, datasets: app?.datasetGroupsInfo || [] }
}

function isObject(value: unknown) {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

export function flattenMigrationNodes(nodes: any[], folders = false): any[] {
  return (nodes || []).flatMap(node => [
    ...((folders ? !node.leaf : node.leaf) ? [{ id: String(node.id), name: node.name }] : []),
    ...flattenMigrationNodes(node.children, folders)
  ])
}
