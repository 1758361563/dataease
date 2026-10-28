# 数据刷新：阶段 1 实现与本地验收

开发基线：`367a204705`（2026-10-28 本地最新提交）。开发分支：`feature/etl-refresh`。

## 已实现

- 管理员入口「集成中心 → 数据刷新」，包含任务、运行记录、SQL 版本和设置。
- 三项刷新任务顺序执行；保留旧 Python 的占位符、分号拆分、事务提交、35 天窗口和提交后验收口径。
- 正式／测试 SQL 包分别导入、校验、激活及回退。SQL 原文和压缩包保留，原文不能编辑；哈希不一致、依赖不符、事务内 DDL、测试包写正式表时拒绝。
- 全局开关默认关闭，绑定实例 ID。硬关闭环境变量同时阻止正式和测试运行。
- Quartz 使用上海时区及 DO_NOTHING；每次触发再检查开关，执行交给单线程执行器。
- 独立连接持有 GET_LOCK；锁监测发现连接丢失时取消当前语句。SQL 异常只展示 SQLState 和错误码。
- `etl_run_log` 继续作为运行结果来源，正式／测试 kind 分离；运行记录按批次展示。
- 飞书新鲜度查询限定 `kind='refresh'`。

## 本次实现明确的规则

1. 启用事务内 DDL 检查。
2. 单任务手动运行要求上游最近一条同 kind、同 window_end 记录成功，并在成功 note 中写入采用的上游记录 ID。
3. 启动时，在取得刷新锁后把遗留的手动 running 记录改为 fail。测试初始化中断也标记失败，每包只执行一次。
4. 测试禁止时段为上海时间 `[02:30,05:00)`；05:00 可以运行。
5. 实例 ID 优先使用 ECS IMDSv2；部署脚本每次启动时重新读取宿主机 ID，通过环境变量注入，覆盖复制的 `.env` 中可能残留的 ID。
6. 新增 `company_etl_batch` 保存批次的包版本、数据源、窗口和任务选择。它不保存任务状态，运行状态仍只存在于 `etl_run_log`。已经开始的批次使用原版本续跑。
7. 已存在 running 的重复触发直接结束，不会把后续任务记为 blocked。启动恢复才会执行已有 running。
8. 没有批次快照的旧执行器 running 记录会显示提示，需核对原执行器；不会猜测它使用的 SQL 版本。
9. 开关关闭时当前正在执行的任务完成后停止后续任务；重新开启开关会检查可续跑批次。

## SQL 包交接

沿用方案第 6.2 节格式，文件为 UTF-8 无 BOM。压缩包与解压内容分别限 16 MB，最多 32 个 ZIP 条目；实际上传还受 BI 现有 multipart 和反向代理上限约束。三个任务的名称、目标表、依赖、窗口字段和验收基线按已确认口径校验。正式包的 `init` 为 `[]`。

测试包初始化数组使用如下结构；`sql_sha256` 是该文件 UTF-8 原文的 SHA256：

```json
"init": [
  { "sql": "init/init_dataease_test.sql", "sql_sha256": "64位十六进制哈希" }
]
```

测试表使用 `<正式表>_dataease_test`，暂存表使用 `<正式表>_dataease_test_refresh_stage`。初始化可从正式表 `LIKE` / `SELECT`，写入目标必须是测试表；刷新脚本的目标和上游引用由 etl_migration 提供。导入器只校验原文。

初始化失败后，先检查已创建的测试表，再使用经确认的新包版本处理。初始化状态不会自动重置为可重复执行。

## 自动化验证

本地 JDK 21；`test-etl-local.ps1` 使用既有 DataEase 编译产物和依赖缓存，编译此次修改并运行 JUnit。默认只运行无外部数据库的测试。

```powershell
./deploy/companybi/scripts/test-etl-local.ps1 -DependencyCheckout D:/Coding_project/scent_library/test/dataease
```

MySQL 集成测试仅接受 `127.0.0.1:33328/qiwei_new_bi`，使用独立容器；测试会重建该库里的 fixture 表、`etl_run_log` 和五张 ETL 元数据表。此端口必须指向测试容器。

```powershell
docker run -d --name companybi-etl-mysql57-20261028 -p 127.0.0.1:33328:3306 -e MYSQL_ROOT_PASSWORD=etl-test-only -e MYSQL_DATABASE=qiwei_new_bi --memory=768m mysql:5.7.44 --server-id=281 --log-bin=mysql-bin --gtid-mode=ON --enforce-gtid-consistency=ON --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci
$env:ETL_TEST_MYSQL_PASSWORD='etl-test-only'
./deploy/companybi/scripts/test-etl-local.ps1 -MysqlUrl 'jdbc:mysql://127.0.0.1:33328/qiwei_new_bi?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8'
```

前端生产构建使用 `npm run build:base`，已经通过。全量 `vue-tsc` 会在既有依赖文件上报错：当前 TypeScript 与 `@types/node` 版本不匹配，报错不在本次新增的页面代码里，也不影响生产构建。

在完整 Maven 环境中也可以运行：

```sh
mvn -f core/pom.xml -pl core-backend test -DskipTests=false -Dtest=EtlCoreTest,EtlWiringTest
node --test feishu-integration/src/etl-status.test.mjs
node --experimental-strip-types --test core/core-frontend/src/utils/companyNav.test.ts
```

真实 SQL 快照来自 etl_migration 的三份 `sql/refresh.sql`；测试资源的 `.sha256` 由原 Python `statements()` 函数生成，Java 逐语句对比。重新生成命令：

```sh
python deploy/companybi/scripts/snapshot-etl-parser.py ../etl_migration/scripts/etl_daily.py core/core-backend/src/test/resources/company-etl
```

## 与 Leo 一起做的本地页面验收

1. 使用本分支构建并启动独立本地 BI；新功能入口为 `/#/integrations/refresh`。
2. 验证管理员可访问，普通用户看不到入口，直接请求 `/company/etl/**` 返回无权限。
3. 在数据源模块创建本地 MySQL 刷新专用数据源，在设置页选择它；确认全局开关初始关闭。
4. 上传 etl_migration 提供的测试包，查看 SHA256、来源提交、校验状态和 SQL 只读预览。
5. 激活测试包，输入确认语执行初始化，检查初始化状态；在允许时段运行测试链。
6. 查看三个任务的开始／结束时间、总行数、验收结果和上游阻塞原因。修改包哈希、依赖或写入正式表后重新上传，应看到 rejected 原因。
7. 对照方案的正式／测试表指标比对清单进行数据验收。

阶段 2 的临时 ECS 部署、真实 RDS 测试表初始化、正式职责切换和秉坤接入继续按原方案推进。秉坤先评估跨源查询，再决定快照方案。

## 部署与回滚

`TZ=Asia/Shanghai` 已加入 Compose。`COMPANY_BI_ETL_HARD_DISABLE=true` 可在部署层关闭全部 ETL 执行。宿主机需可用 curl 才能注入 ECS ID；容器内可直接访问 IMDSv2 时也能自行取得 ID。

回滚到没有 ETL 类的镜像前，先关闭全局开关，等待当前批次结束，确认 `COMPANY_ETL` 分组的 Job / Trigger 已删除，再运行 `rollback.sh`。保留元数据表及历史记录。

本地机器没有 ECS ID 时仍可执行测试包。完整的正式开关流程可在隔离的本地测试进程注入专用 `COMPANY_BI_INSTANCE_ID` 后验证；部署脚本会重新解析宿主机 ID。
