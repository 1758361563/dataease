# 仪表板全局参数：第一阶段实施记录

日期：2026-10-10。范围：定义模型、持久化、日期默认值解析、仪表板共享字段元数据与依赖校验。

## 已实现

- `DashboardCalculationConfig` 保存 DATE 参数及共享计算字段定义，当前值不进入配置。参数和字段使用字符串序列化的稳定 ID。
- 配置存于仪表板的 `calculation_config`，编辑版和正式版分别落入 snapshot 与 core 表。保存、重开、发布、恢复沿用已有版本流程；旧客户端未传配置时保留原配置，空列表明确清空。
- 共享字段属于“仪表板＋数据集”，同范围卡片复用同一个字段 ID；不写入公共数据集字段，也不复制为多个图表私有字段。字段元数据带 `fieldScope=DASHBOARD` 和 `dashboardId`。
- 输入公式可使用 `[DYNAMIC_PARAMS.期初]`，保存时转为 `[DVP.参数ID]`；字段名称引用转为 `[字段ID]`。引用重命名后继续有效。
- 保存校验重复名称、ID 冲突、数据集权限、跨数据集引用、缺失参数和共享字段循环依赖；删除时检查仍存续图表的轴字段和私有计算字段引用。
- 固定日期和时间宏统一以一次传入的时间基准及仪表板时区解析。默认时区 `Asia/Shanghai`。支持 `today`、`yesterday`、`month beginning`、`monday last week`、`sunday last week` 及 `yesterday %yyyy-MM-dd / %yyyy-MM-01 / %yyyy-01-01` 的三层花括号宏。
- 字段加载区分编辑版和正式版；编辑器允许未保存的新卡片及切换数据集。已有无配置仪表板不产生共享字段。
- 前端加载和保存携带配置，新增参数配置 TypeScript 模型和校验/默认值解析 API 封装。

## 接口与数据

`POST /dataVisualization/validateCalculationConfig` 和 `POST /dataVisualization/resolveCalculationDefaults` 接收仪表板请求，包含 `id`、`type: dashboard` 和 JSON 字符串 `calculationConfig`。前者返回规范化定义，后者返回参数 ID 到 ISO 日期的映射。两者使用仪表板编辑权限。

数据结构示例（ID 仅为示意）：

```json
{
  "version": 1,
  "timezone": "Asia/Shanghai",
  "parameters": [
    {"id": "1001", "name": "期初", "type": "DATE", "required": true, "default": {"mode": "FIXED", "value": "2026-04-01"}},
    {"id": "1002", "name": "期末", "type": "DATE", "required": true, "default": {"mode": "MACRO", "expression": "{{{sunday last week}}}"}}
  ],
  "datasetFields": [
    {"id": "2001", "datasetGroupId": "3001", "name": "YTD期间", "groupType": "d", "deType": 2,
     "expression": "if(and([取值时间]>=[DYNAMIC_PARAMS.期初],[取值时间]<=[DYNAMIC_PARAMS.期末]),1,0)"}
  ]
}
```

字段语法和聚合表达式未改写。第一阶段只提供共享字段元数据；参数类型绑定、公式编译及 SQL 实际计算在第二阶段完成。

## 验证与运行边界

新增两组 JUnit 测试，共 8 项，覆盖日期宏、稳定引用、无效依赖、参数 ID 与字段 ID 子串冲突、跨仪表板/版本隔离、保存权限与删除依赖。持久化测试在独立 H2 数据库中执行真实新增迁移及生产 MyBatis 的读取、snapshot、发布语句，验证重开与发布后的结果。

修改的 Java 源文件已使用本机 JDK 21 和现有缓存依赖完成编译，8 项测试通过。前端修改文件 ESLint 无错误，仅有原有未使用变量警告。完整 Maven 打包未执行，本机未配置 Maven 命令。

全项目 `vue-tsc --noEmit` 已尝试，退出码为 2：现有 TypeScript 4.9.5 无法解析已安装的 `@types/node 26.1.1` 中 `ffi.d.ts` 的新类型语法，诊断均位于该依赖。此项不能记作通过；本阶段未调整项目依赖。

迁移文件已提供于 `db/migration` 和 `db/desktop`。尚未向运行数据库应用迁移、打包部署或重启服务；运行中的页面不会出现新参数控件。

## 第二阶段接续

1. 仪表板顶部参数控件、固定/宏默认值设置，以及同仪表板同数据集共享计算字段的编辑入口。
2. 草稿值/生效值与现有查询按钮衔接，无按钮改值刷新，有按钮点击后统一生效。
3. 服务端按一次查询的参数快照绑定 DATE 值，进入原有表达式编译与聚合层，验证 YTD 期间及聚合指标计算结果。

复制 ID 重映射、模板/导入导出完整适配及缓存隔离按第三阶段完成。
