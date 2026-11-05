# 透视表自定义总计修复交接

日期：2026-11-05。代码已在本地修改，未Commit、Push或部署；由用户另行安排本地AI按日常简化流程发布。

## 问题与修复

- 占比总计公式合法、数据集字段ID也正确，但指标含fieldInstanceId时显示“-”。后端临时总计指标丢失实例身份，返回旧dataeaseName；前端按实例resultKey取值，无法匹配。
- `TablePivotHandler.getCustomFields`按图表每个指标实例解析总计设置，优先匹配resultKey，再回退旧dataeaseName；临时字段继承原指标的fieldInstanceId/metricInstanceId。行/列总计、小计、交叉总计均沿用同一构造逻辑。
- 新增`pivotTotalIdentity.ts`统一前端总计配置解析；打开设置时兼容迁移旧配置。四个总计/小计指标选择器按resultKey选择，重复放置同一数据集字段时可分别配置，原标签及公式保留。
- 公式编辑新增`customTotalExpression.ts`。保存时拒绝未找到、已失效或重名引用，错误时不关闭弹窗、不发出配置变更，也不覆盖旧公式；不再生成`[undefined]`。

## 发布范围

前端和后端需要同一镜像一起更新。无新增服务、环境变量或数据库结构迁移；不需要修改现有图表或业务数据库。

部署后，已保存的正确公式可直接继续使用，无需重新录入。例如原数据为新客4、开单9时，总计按当前筛选条件计算并显示44.4%；本次用聚焦测试验证结果标识与显示链路，没有查询业务库验证真实数据。

提交时包含新增源码、测试和脚本。后端新测试已加入`.gitignore`的精确白名单，勿漏掉未跟踪文件。不要混入日志、构建缓存或私有诊断资产。

## 已完成的本地验证

仓库根目录运行：

```powershell
./deploy/companybi/scripts/test-pivot-custom-totals-code.ps1
```

使用现有`target/dashboard-phase1/local-deps`和SDK编译类，编译修改的后端类并运行5项JUnit测试，全部通过。测试不启动Spring、服务或数据库；覆盖旧配置/实例配置、重复指标、metricInstanceId兼容、结果键及4/9结果。

前端目录`core/core-frontend`运行：

```powershell
node --test tests/pivotCustomTotals.test.cjs
```

8项通过，包括实际总计渲染函数、配置迁移与优先级、重复放置、行列总计及小计、0值、长ID、公式失效拦截、两个实际Vue脚本和模板编译。修改文件ESLint通过。

扩展执行`node --test tests/pivot*.test.cjs`：15项通过、1项失败。失败为既有`pivotAppearance.test.cjs`抽取主题函数时缺少parseJson注入；使用HEAD版本的table-pivot.ts重跑，同样失败。本次未修改该外观测试，不将其算作通过。

未运行完整前后端构建、服务器验收或业务SQL。后续按用户授权的简化部署流程构建镜像并发布即可；详细问题状态在known-issues.md。

## 同批追加：重复日期维度的小计选择与显示

- 用户需要月 → 周 → 周区间的行维度、星期作为列维度，每个月底显示“小计”。旧选择器把同一日期字段的多个放置项都保存成同一个dataeaseName；下拉框选中重叠，且与渲染时的实例resultKey不匹配，导致小计消失。
- 新增pivotSubtotals.ts供设置面板和渲染器共同使用：每个可小计层级以独立resultKey标识，同名日期项标注“月”“周”“周区间”；最末明细层不作为小计层级。兼容旧字段名配置，显式实例配置优先，空选择保持为空。
- 月小计与周小计可分别勾选。实现观远截图效果时：行汇总 → 小计勾选显示，选择“取值时间（月）”勾选显示，选择“取值时间（周）”取消显示，位置选底部，业绩聚合选求和。无需开启总计。
- 新增测试：前端目录执行`node --test tests/pivotSubtotals.test.cjs`，4项通过。覆盖真实选择器切换/保存/重新初始化、旧配置迁移、混合身份字段、Vue编译；通过已安装S2的真实层级构造器验证月末小计行、独立周小计、关闭总计仍显示小计，以及按星期列分别求和。使用合成数据，不连接业务库。
- 联合执行`node --test tests/pivotSubtotals.test.cjs tests/pivotCustomTotals.test.cjs tests/pivotRepeatedDate.test.cjs tests/pivotMeasureTotals.test.cjs`，14项通过，覆盖此前自定义总计、重复日期放置、指标布局与导出回归；本次修改的三个前端源码文件ESLint通过。
- 本次追加仅涉及前端，需与前面的后端总计修复一同交给部署AI提交与构建。当前仍为本地修改，未部署。

## 同批追加：星期名称必须按星期几聚合

- 问题：列维度设置“星期名称”后，旧实现只把具体日期格式化为星期名称，查询仍按完整日期分组，因此重复出现很多套星期列。
- 修复：Dimension2SQLObj在`dateStyle=y_M_d`、`dateShowFormat=weekday_name`时按星期几生成查询分组键，周一为1、周日为7。沿用日期/字符串/时间戳的解析流程。普通日、月和周分组保持原逻辑；原始日期筛选仍作用于业务日期。
- 前端支持新星期分组键的名称格式化，默认按周一至周日排序，保留显式降序/自定义排序。入口文案更新为“星期几（按周一至周日分组）”。旧图表同一设置刷新查询即可生效，无需另建业务表或修改ETL。
- 部署后保持行维度“月、周、范围”，列维度“星期”选择该选项，指标为业绩求和；刷新数据。各周共用相同星期列，月小计在同一星期列累加。
- 验证：上述后端脚本增加编译Dimension2SQLObj并运行PivotWeekSqlTest，原5项JUnit和周/月SQL测试通过；星期查询验证了三种输入类型的MySQL方言转换，并在本地临时H2中实际执行生成表达式，验证七类分组、跨年/跨月星期一合并求和、周日为7。H2按US locale模拟MySQL的Sunday=1约定，不访问任何远程数据库。
- 前端联合运行`node --test tests/pivotSubtotals.test.cjs tests/pivotCustomTotals.test.cjs tests/pivotRepeatedDate.test.cjs tests/pivotNullableSort.test.cjs tests/pivotMeasureTotals.test.cjs`：16项通过，包括真实S2两周数据共用七列及各星期月小计、旧配置默认排序、七个星期标签；本次三个前端源码文件ESLint通过。
- 本次需前后端同批构建，仍未Commit/Push或部署。与前述总计、小计修复一起发布，无新增数据库迁移或服务。

## 星期显示的两种可切换模式

- 入口：透视表日期维度设为日精度，在日期显示菜单选择以下任一项，每个字段放置项独立保存。
- “星期几（按周一至周日分组）”：`dateShowFormat=weekday_name`，沿用上节按星期几聚合。若列层级为月 → 周 → 星期，则分别在每个月、每周下面展开，不跨父层级混合。
- “按具体日期分组（仅显示星期名称）”：`dateShowFormat=weekday_date_name`，查询与普通日精度相同，每个具体日期仍为独立分组，前端仅改星期名称；两周的星期一可以显示为两个独立列。采用普通日期排序，不强制按星期几重排。
- 切回普通日期格式会清除星期模式。旧`weekday_name`配置保留按星期几分组行为。
- 验证：真实DimensionItem菜单处理函数切换两种模式及普通日期、配置JSON保存、Vue脚本/模板编译通过；后端断言日期显示模式的SQL表达式与普通日精度完全一致。相关前端16项及后端5项JUnit和日期SQL测试全部通过。
