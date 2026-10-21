# 仪表板默认细节配色

仪表板默认的 Tab、卡片边框与阴影、拖拽手柄、编辑菜单和完成按钮使用 `src/custom/company-bi/style/tokens.less` 的共享蓝灰色变量：

- 底轨 `--bi-bg-subtle: #F3F5F8`，轻底 `--bi-bg-soft: #FAFBFD`，白色卡片 `--bi-bg-surface`。
- 悬停 `--bi-bg-hover: #EDF3FB`，按下 `--bi-bg-pressed: #E3EBF7`。
- 边框 `--bi-border: #E7ECF2`，较强边框 `#D8E1EE`。
- 主要文字 `#37465F`、次要文字 `#64748B`、未选中 Tab `#7B879B`。

仪表板编辑与预览容器内的通用控件填充和文字变量使用此色系。公司原有边框 token 本身是全局加载，本次更新其默认色值；管理页面和数据大屏不新增局部填充/文字覆盖。

Tab 沿用原来的尺寸、滚动和切换行为。旧默认文字色与浅灰标题背景通过 `dashboardUiColor` 在渲染时映射到变量，支持 RGB/RGBA 和十六进制写法；其他自定义颜色、透明度、背景图和大屏分支不变。旧默认颜色与用户手动设置成相同颜色无法区分。没有新增持久化迁移，也不修改保存的 Tab 标题、结构或配色值。

修复旧 `dashboard-density.less` 仍以 `!important` 固定筛选器 16px 字号和 36px 高度的问题；尺寸与排布由筛选器组件自己管理，使已有的紧凑配置和缩放补偿实际生效。

没有修改图表数据系列、阈值、涨跌色、筛选或查询逻辑。验证包括 `tests/dashboardUiPalette.test.cjs`、`tests/queryAppearance.test.cjs`、完整公司 Less 样式层编译、Tab Vue 编译及 ESLint，并加载官方全局样式、完整公司样式和真实 DeCustomTab 做浏览器切换与默认/自定义色值检查。
