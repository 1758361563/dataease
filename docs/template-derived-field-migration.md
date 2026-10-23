# 应用模板：计算字段、分组字段与仪表板配置迁移

选择目标数据集导入 DET2APP 时，物理列先按来源表和原始列名匹配。单表数据集允许源、目标表名不同；多表数据集要求各来源表能唯一匹配。

计算字段、复制字段和分组字段的处理：

- 递归收集公式依赖，检查缺失依赖和循环引用。
- 目标同名字段的类型、重绑后的公式/来源、分组规则一致时复用。
- 目标缺少字段时，在具备目标数据集管理权限的前提下补建元数据。已有字段不会被覆盖。
- 名称歧义、定义不同、物理列缺失或权限不足时明确阻断。补建和仪表板保存沿用 `saveCanvas` 事务。

仪表板共享字段和全局参数继续使用独立的仪表板作用域。模板解压时分配新的作用域 ID；选定目标数据集后，重绑共享公式及卡片私有公式中的数据集字段引用。保留参数定义、默认规则、时区，清除导出时的临时查询值。数字常量、名称和字符串字面量不参与 ID 替换。

分组字段保存的是裸来源 ID，单独重绑；各分组名称、范围和“其他”规则保留。图表字段的展示角色、聚合、格式等仍保留原配置。

## 本地验证（2026-10-23）

- 使用本机 Java 21、已有依赖缓存编译变更源码和相关测试；没有修改运行中的服务文件。
- JUnit：`TemplateDerivedFieldRebindTest`、`DashboardCalculationTest`、`DashboardCalculationPersistenceTest`、`DashboardParameterQueryTest`、`DashboardCalculationLifecycleTest`。包括真实重绑入口的跨 ID、共享/私有引用、缺失字段补建与权限拒绝、多表同名列测试，以及隔离 H2 的配置保存/草稿/发布回归。
- 前端：`node --test tests/dashboardMigration.test.cjs tests/dashboardParameterRuntime.test.cjs tests/dashboardFormulaReferences.test.cjs`。
- 结果：后端 30 项测试全部通过，前端 3 组回归全部通过。保存入口 `saveCanvas` 也使用模拟存储验证了重绑结果不会被公共导入逻辑重复替换；这不等同于真实服务器端到端验收。
- 本次不涉及塔内业务数据库写入；未进行塔内真实仪表板的端到端验收。目标后端更新后，新导入才能使用本次逻辑。旧模板若未携带共享字段/参数定义，需要从支持这些定义的源端重新导出。

标准 Maven 环境可在仓库根目录执行：

```sh
mvn -pl core/core-backend -am test -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false -Dtest=TemplateDerivedFieldRebindTest,DashboardCalculationTest,DashboardCalculationPersistenceTest,DashboardParameterQueryTest,DashboardCalculationLifecycleTest
```
