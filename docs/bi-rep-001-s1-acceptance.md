# BI-REP-001 / S1 验收记录

日期：2026-09-07。状态：已验收。

柱线图左右轴分类集合或顺序不同时，G2 在同步分类轴前已分别将分类转换为数字位置，导致后续系列位置错位。真实故障图左轴 12 类、右轴 14 类，符合触发条件。

修复在空值策略处理后，按分类键稳定重排完整记录，并显式设置共享分类域 `meta.field.values`。保留左轴顺序，追加右轴独有分类，不填造零值，不改变指标身份及 Tooltip 数据。所有柱线变体共用此逻辑，替代堆叠图的局部原地排序。

验证结果（在 core/core-frontend 执行）：

- `node src/views/chart/components/js/panel/charts/others/chart-mix-data.selftest.cjs`：11 组定向检查通过；覆盖非同序、缺类、空轴、零值/空值、分组、顺序恢复及序列化重载，验证 Tooltip 左右轴指标身份。
- 修改的两个 TypeScript 文件定向 ESLint 检查通过；`git diff --check` 通过。
- 全项目 `npm.cmd run ts:check` 受既有 `@types/node/ffi.d.ts` 与 TypeScript 4.9.3 的语法兼容问题阻挡，未宣称全项目类型检查通过。

用户原始验收反馈：“验证通过，更新文档并且commit。”据此记录 S1 阶段验收通过；用户未给出逐项测试明细，不补造记录。

下一阶段为 S2 聚合指标排序，尚未开始。本次仅修改前端，保留原有指标卡工作区修改。

完整本地计划和开发记录位于源码仓库外的 `BI-docs/DataEase正式开发/`，已同步更新验收状态。
