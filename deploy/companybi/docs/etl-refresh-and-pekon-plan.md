# DataEase 每日数据刷新与秉坤 SQL Server 接入方案

版本：v2（2026-10-28），取代 2026-10-27 阶段 0 对话中的口头方案。
状态：**阶段 1 已在分支 `feature/etl-refresh` 实现，尚未部署临时 ECS。** 2026-10-28 完整编译 524 个后端源文件通过；29 项测试（含 MySQL 5.7.44、GTID）通过。本地页面验收和 ECS 更新仍等 Leo 安排。实现说明见 [etl-refresh-local-testing.md](etl-refresh-local-testing.md)。
范围：在 DataEase（Company BI，分支 `company/bi`）内替代 39.103.208.62 上的 `qiwei-etl-daily`，并评估、实现秉坤 SQL Server 接入。

信息来源标注：
- 【实测】本次在仓库或临时 ECS 上直接查到的事实；
- 【etl 侧】etl_migration 一侧 2026-10-28 提供的已核对信息；
- 【推断】从代码推断、尚未运行验证的结论；
- 【待定】需要 Leo 或 CEO 拍板。

---

## 0. 摘要

1. **可行，落点清楚。** 新功能放在 `io.dataease.company.bi.etl` 包，沿用 Company BI 已有的建表方式、管理员权限、审计日志和 `GET_LOCK` 单实例模式。前端放在「集成中心 → 数据刷新」。
2. **执行规则逐条对齐现有 Python 程序** `etl_migration/scripts/etl_daily.py`（第 82–319 行），不重写口径，也不改 SQL。
3. **相对 10-27 的口头结论，有两处更正：**
   - **Quartz 不是只存在内存里。** fit2cloud 的 `quartz-spring-boot-starter` 1.0.8 配置的是 JDBC 集群存储（`StdJDBCDelegate`、`isClustered`），触发器持久化在元数据库 `companybi_meta` 的 `QRTZ_*` 表里【实测：jar 内配置项和 `V2.0__core_ddl.sql` 建表语句】。所以整机复制时，触发器会随元数据库一起被复制。本方案把开关校验放在**每次触发执行时**，而不只在启动注册时。
   - **`.gitignore` 并不是被改成了忽略后端源码。** 仓库原本就采用白名单策略（`/core/core-backend/src/main/**` 默认忽略，新文件逐个放行）。新增的后端文件必须在 `.gitignore` 里逐个加放行规则，否则不会进入 Git。
4. **新发现的风险：**
   - 飞书新鲜度检查不按 `kind` 过滤（见第 12 节）。
   - 数据源配置的 AES 密钥是公开源码里的常量（见第 11 节）。
   - 39.103.208.62 的内存不够容纳整套 DataEase（见第 17 节）。
5. **需要 Leo 拍板的事项**汇总在第 20 节。

---

## 1. 硬性边界（实现中的强制约束）

| 边界 | 在实现中如何落实 |
|---|---|
| 不输出密码、密钥或口令 | 连接只通过 DataEase 数据源模块获取；日志、`note` 字段、接口返回和异常信息都经过脱敏，不拼接 JDBC URL；测试用 fixture 不放真实凭据；每次提交前做一次凭据扫描 |
| 不在 39.103.208.62 上部署或运行 | 阶段 0–4 的所有动作都在临时 ECS 39.99.137.73 和本地完成 |
| 不改 SQL | 导入时只做校验，不改写。阶段 2 的测试 SQL 由 etl_migration 提供改写好的版本 |
| 同一张表只能由一个调度方写入 | 全局开关默认关闭；开关绑定实例；每次执行先拿 `GET_LOCK`；正式表的手动触发同样受开关约束（第 8 节） |
| 每个阶段结束后停下 | 本文档就是阶段 0 的交付物，确认后才进入阶段 1 |

---

## 2. 阶段 0 事实（更新版）

### 2.1 代码与版本
- DataEase 2.10.25，Spring Boot 3.5.15。分支 `company/bi`；本地 HEAD 是 `2afb1b4d3e`（已包含 10-28 提交的数据集预览改动，见第 21 节），**尚未推送**。
- 临时 ECS 运行的镜像是 `companybi:2.10.25-20261023-3598e462`【实测 10-27】。本地 HEAD 比它多三类内容：`674d130f76`（飞书集成运行时）以及 10-28 的三个数据集预览提交。用 HEAD 构建并部署，会把这些改动一起带上线【待定：分支基线，见第 20 节】。
- 元数据库的 Flyway 版本是 2.10.26【实测】。

### 2.2 部署结构（临时 ECS）
- 两个容器：`companybi-app`（端口只绑 127.0.0.1:30005，内存上限 2g，堆 1g，JVM 参数含 `-Duser.timezone=Asia/Shanghai`）和 `companybi-mysql`（MySQL 8.4.5，元数据库，768m）。
- 主机时区 Asia/Shanghai，内存 7.5G，可用约 5.8G【实测 10-27】。
- **10-28 12:20 前后，SSH 到 39.99.137.73 连续两次出现 banner exchange 超时**，原因未查明。本节以后的 ECS 侧核对（元数据库 `QRTZ_*` 的实际行数、容器能否访问 ECS 元数据服务）需要等 ECS 恢复后补做【待验证】。

### 2.3 调度框架
- `com.fit2cloud:quartz-spring-boot-starter:1.0.8`，配置为 `quartz.enabled=true`、`scheduler-name=deSyncJob`。
- JobStore 是 JDBC，开启集群（`isClustered`），使用元数据库的数据源，表前缀 `QRTZ_`【实测：jar 配置项字符串和 `V2.0__core_ddl.sql:3737` 起的建表语句】。
- 现有 `job/schedule/ScheduleManager.addCronJob` 没有设置 misfire 策略，所以 CronTrigger 默认为 `MISFIRE_INSTRUCTION_SMART_POLICY`，效果等同于"错过后补跑一次"，**不能直接复用**。
- 现有定时任务只覆盖数据集抽取、数据源状态检查、清理和 xpack 同步，没有"执行任意 SQL 并验收"的能力。

### 2.4 数据源
- 元数据库里目前只有一个业务数据源「塔内主数据库」（MySQL）【实测】。
- `core_datasource.configuration` 由 MyBatis 拦截器透明加解密（`config/MybatisConfig.java:26`），密钥情况见第 11 节。
- 数据集层面经 SQL 解析只允许 SELECT；`CalciteProvider.executeUpdate` 有写入能力，但只供数据填报使用。
- **刷新账号**【etl 侧】：使用现有账号 `qiweibi`。它能 SELECT 各个 `com_newretail_*` 源库，并对 `qiwei_new_bi` 有完整写权限。按要求单独建一个"刷新专用"数据源，口令由 Leo 在界面填写；仪表板继续使用原来的只读数据源。

### 2.5 `etl_run_log`【etl 侧已核对】
- 唯一键 `uk_plan(etl_name, kind, planned_at)`；`kind` 和 `trigger_type` 都是 `varchar(16)`。
- 正式运行 `kind='refresh'`，测试运行 `kind='refresh_test'`（12 个字符）。不改表结构。

### 2.6 RDS【etl 侧】
- MySQL 5.7.44，开启 GTID（`enforce_gtid_consistency=ON`），时区跟随系统（CST）。

### 2.7 秉坤网络【实测 10-27】
- 临时 ECS 宿主机和容器内的 TCP 测试都是 FAIL；DNS 能解析到 47.118.246.113。
- 按 etl 侧第 6 条：**不另建隧道，也不改白名单**。部署后由 Leo 在 DataEase 数据源界面配置秉坤连接，按实际情况选直连或 SSH。

---

## 3. 数据源模块获取连接的入口（补充 etl 侧第 6 条）

结论：刷新任务和秉坤任务都只通过下面的**入口 A** 取连接，不读取地址和账号来自己拼 JDBC 连接。SQL Server 数据源走的是同一个入口，SSH 隧道对它同样适用。

| 入口 | 位置 | 说明 | 本方案是否使用 |
|---|---|---|---|
| A. 单条连接 | `core/core-backend/src/main/java/io/dataease/datasource/provider/CalciteProvider.java` 第 451 行：`public ConnectionObj getConnection(DatasourceDTO)` | 每次新建一条非池化连接：先按类型解析配置（第 479–493 行，包含 `case sqlServer -> Sqlserver.class`），再由 `JdbcUrlSecurityPolicy` 校验驱动和 URL，再调用 `startSshSession(configuration, connectionObj, null)` 建立**这条连接专属的 SSH 会话**，最后用 DataEase 的驱动类加载器建连。返回的 `ConnectionObj` 实现了 `AutoCloseable`，`close()` 会依次关闭 JDBC 连接、断开 SSH 会话、释放本地端口（`sdk/.../dto/ConnectionObj.java:20–33`） | **使用**。刷新任务的每个批次、锁连接和秉坤读取都走这里 |
| B. 连接池 | 同文件第 1865 行：`private Connection getConnectionFromPool(Long dsId)` | 访问修饰符是 private。连接池由 `buildSchema` 创建，设置了 `setDefaultQueryTimeout(queryTimeout)`，默认 30 秒（`Configuration.java:41`）；SQL Server 分支在第 1088–1103 行 | **不使用**。30 秒超时会让刷新 SQL 失败；跨源数据集（方案 A）由 DataEase 自己经它查询 |
| C. Provider 工厂 | `sdk/extensions/extensions-datasource/.../factory/ProviderFactory.java:23`：`getProvider(type)` | 内置类型（包括 `sqlServer`、`mysql`）都返回 `calciteProvider` 这个 bean | 通过它拿到入口 A |
| D. 读取数据源配置 | `core/core-backend/src/main/java/io/dataease/datasource/manage/DataSourceManage.java:254`：`getDs(Long id)` | 返回已解密的 `DatasourceDTO`，只在内存中传给入口 A，不落日志 | 使用。阶段 1 需要核实它在无登录上下文的调度线程里有没有权限拦截 |

SSH 隧道的实现：
- `sdk/extensions/extensions-datasource/src/main/java/io/dataease/extensions/datasource/provider/Provider.java` 第 297–342 行（`startSshSession` 和 `initSession`）。使用 JSch 做本地端口转发，本地端口在 10000–20000 之间取空闲端口。
- 开启 SSH 后，`Configuration.getLHost()` 返回 `127.0.0.1`，`getLPort()` 返回隧道端口（`Configuration.java:54–67`）。`Sqlserver.getJdbc()` 和 `Mysql.getJdbc()` 都用这两个值拼 URL（`datasource/type/Sqlserver.java:17–33`），所以 SQL Server 自动走隧道。
- 已知限制：`session.setConfig("StrictHostKeyChecking", "no")`，不校验 SSH 主机密钥；会话连接超时 5 秒。

超时说明：
- MySQL 类型的默认 URL 参数是 `connectTimeout=5000`，没有 `socketTimeout`（`datasource/type/Mysql.java:13`），所以入口 A 返回的连接读取不会超时。
- 执行器自己创建 `Statement`，不设置 `queryTimeout`，然后按旧程序执行四条 `SET SESSION ... = 28800`。

---

## 4. 总体架构

```
集成中心 → 数据刷新 (Vue, 仅管理员)
        │ REST /company/etl/**
        ▼
EtlController ── CompanyAdminGuard + CompanyAuditRecorder(操作审计)
        │
        ├─ EtlPackageService      导入 zip、校验、版本、激活
        ├─ EtlSwitchService       全局开关 + 实例绑定
        ├─ EtlQuartzRegistrar     启动/开关变化时注册或删除 Cron 触发器（DO_NOTHING）
        │        └─ EtlQuartzJob  触发时只把任务交给执行线程，立即返回
        ├─ EtlChainRunner         批次：锁 → 顺序 → 依赖 → 认领 → 执行 → 验收 → 记录
        │        ├─ EtlSqlScript         占位符替换、拆分、拒绝规则（纯函数）
        │        ├─ EtlStatementExecutor 事务语义、回滚、会话超时
        │        ├─ EtlAcceptance        刷新前后指标对比与基线检查
        │        ├─ EtlRunLogRepository  qiwei_new_bi.etl_run_log 读写
        │        └─ EtlLock              GET_LOCK（专用连接）
        └─ EtlResumeOnStartup     启动后按 etl_run_log 续跑 36 小时内的 running 批次
                 │
                 ▼  所有连接：DataSourceManage.getDs → CalciteProvider.getConnection
           「qiwei_new_bi 刷新专用」数据源 (qiweibi)      「秉坤」数据源 (阶段 4)
```

设计原则：
- **单线程执行器**：只有一个 `ExecutorService`，同一时刻最多一个批次。Quartz 工作线程只负责投递任务、立刻返回，不会长时间占住 DataEase 共用的 Quartz 线程池。
- **纯逻辑与 IO 分离**：脚本解析、窗口计算、验收判断、续跑决策都做成纯函数，并注入 `Clock`，方便单元测试。

---

## 5. 数据模型

### 5.1 运行记录：沿用 `qiwei_new_bi.etl_run_log`，不另建

| 字段 | 写入规则 |
|---|---|
| `etl_name` | 清单里的任务名，例如 `销售订单数据-线下`（不是物理表名） |
| `kind` | 正式运行写 `refresh`，测试包写 `refresh_test` |
| `status` | 先写 `running`；结束时只能从 `running` 改成 `success`、`fail` 或 `blocked`（`WHERE id=? AND status='running'`，影响行数不是 1 就报错，与 `etl_daily.py:212–223` 一致） |
| `started_at` / `finished_at` | 由执行器按 Asia/Shanghai 格式化成 `yyyy-MM-dd HH:mm:ss` 字符串后写入，**不使用 `NOW()`** |
| `window_start` / `window_end` | 执行器计算出的日期字符串 |
| `planned_at` | 定时触发时取 Quartz 的 `scheduledFireTime`，按 Asia/Shanghai 截到分钟（例如 `2026-10-29 03:00:00`）；手动触发时取触发时刻，精确到秒，同一条链共用这个值 |
| `trigger_type` | `schedule` 或 `manual` |
| `row_count` | 成功时为目标表总行数，与旧程序一致 |
| `note` | 成功时写"35 天窗口刷新并验收通过，耗时 N 秒"；失败或阻塞时写原因。截断到 512 个字符，异常信息先脱敏 |

关键性质：同一 `planned_at` 的唯一键会阻止 DataEase 和旧程序在同一个 03:00 槽位上重复执行，后到的一方认领失败后跳过。这是开关和锁之外的第三道保护，但**不保护手动触发**。

### 5.2 新增元数据表（`companybi_meta`，由 `CompanyBiSchemaInitializer` 以 `CREATE TABLE IF NOT EXISTS` 创建）

| 表 | 主要字段 | 用途 |
|---|---|---|
| `company_etl_setting`（单行） | `scheduler_enabled`、`enabled_instance_id`、`enabled_by`、`enabled_at`、`daily_at`（HH:mm，默认 03:00）、`chain_enabled`、`refresh_datasource_id`、`active_package_id`、`updated_at` | 全局开关、执行时间、绑定的刷新数据源、当前生效的 SQL 版本 |
| `company_etl_task` | `etl_name`（PK）、`enabled`、`sort_order` | 单个任务的启停。目标表、依赖、基线都以生效版本的清单为准，不在这里重复存 |
| `company_etl_package` | `id`、`package_kind`（formal/test）、`run_kind`、`file_sha256`、`source_commit`、`generated_at`、`manifest_json`、`imported_by`、`imported_at`、`status`（imported/active/retired/rejected）、`reject_reason` | 每次导入一条不可变记录 |
| `company_etl_package_file` | `package_id`、`path`、`etl_name`、`role`（refresh/init）、`sha256`、`content`（LONGTEXT） | SQL 原文，导入后不可修改 |

运行记录不进这些表。开关切换、导入、激活、手动触发等操作写入已有的 `company_bi_audit_log`（`CompanyAuditRecorder`）。这是操作审计，不是第二套运行记录。

---

## 6. SQL 包与导入

### 6.1 导入方式：界面上传 zip（推荐）

| 方式 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| 界面上传 zip | 每次导入有人、有时间、有哈希，可回滚到任意历史版本；不依赖宿主机目录 | 需要 etl_migration 生成清单 | **推荐** |
| 挂载目录 | 不需要界面 | 文件被直接覆盖就没有留痕；整机复制时目录容易漏掉；宿主机上任何人都能改 | 不推荐 |
| 从 Git 拉取 | 天然有版本 | DataEase 要持有 Git 凭据，塔内机器也没有 Git | 不推荐 |

### 6.2 包结构（建议格式，需要与 etl_migration 对齐）

```
qiwei-refresh-<kind>-<commit>.zip
├─ manifest.json
├─ 销售订单数据-线下/refresh.sql
├─ 全渠道-销售/refresh.sql
├─ 产品分渠道销售/refresh.sql
└─ init/init_dataease_test.sql          # 仅测试包
```

```json
{
  "schema_version": 1,
  "package_kind": "formal",
  "run_kind": "refresh",
  "source": { "repo": "etl_migration", "commit": "79ae1eb", "generated_at": "2026-10-28 10:00:00" },
  "window_days": 35,
  "tasks": [
    {
      "etl_name": "销售订单数据-线下",
      "target_schema": "qiwei_new_bi",
      "target_table": "销售订单数据_线下",
      "window_field": "orderDate",
      "depends_on": [],
      "sql": "销售订单数据-线下/refresh.sql",
      "sql_sha256": "<hex>",
      "acceptance": {
        "money_field": "actualMoney",
        "history_start": "2026-01-01",
        "baseline": { "month": "2026-07", "rows": 20826, "money": "1901999.6700", "tolerance": "0.01" }
      }
    }
  ],
  "init": []
}
```

另外两张表的基线：`全渠道_销售` 为 132194 行、5973923.4500；`产品分渠道销售` 为 24272 行、5973923.4500。窗口字段分别是 `orderDate` 和 `支付时间(天)`。

### 6.3 导入时的校验（只校验，不改写）
1. zip 结构合法，`manifest.json` 能解析，每个文件的 SHA256 与清单一致。
2. 任务名唯一；依赖都在清单内且不成环；三张表的拓扑顺序与现有程序一致。
3. 每个 `refresh.sql` 都包含三个占位符：`__REQUIRED_WINDOW_START__`、`__REQUIRED_WINDOW_END__`、`__SAFETY_SMOKE_PASSED__`。
4. 全文出现 `RENAME TABLE`，或按行规则拆分后有语句以 `RENAME` 开头，就拒绝导入（与执行时的规则相同，见第 7 节）。
5. 新增的**结构性检查**【待定】：在 `START TRANSACTION` 到 `COMMIT` 之间出现 DDL（CREATE、DROP、ALTER、TRUNCATE、RENAME）时拒绝。原因是 MySQL 遇到 DDL 会隐式提交，破坏"事务中途不能提交"的规则。旧程序没有这项检查；现有三份 SQL 预计都能通过，阶段 1 会用真实文件验证。
6. 包的类型和写入目标要一致：
   - 正式包（`run_kind=refresh`）的 `target_table` 不能以 `_dataease_test` 结尾。
   - 测试包（`run_kind=refresh_test`）的每个 `target_table` 都必须以 `_dataease_test` 结尾；并且 SQL 里任何带反引号的标识符都不能**等于**某张正式目标表名或正式暂存表名（`<正式表>_refresh_stage`）。这是防止测试包误写正式表的安全网，仍然只校验、不改写。
7. 校验失败时，记录状态写 `rejected` 并附原因，原文照样保存，便于排查。

### 6.4 版本与激活
- 导入成功后状态为 `imported`，**不会自动生效**。管理员在界面点"激活"后，上一个生效版本变为 `retired`。正式包和测试包各自独立维护"当前生效版本"。
- 回滚到旧 SQL：重新激活某个历史版本即可，审计日志里留有记录。
- 某个批次开始执行时，把版本号和 SQL 哈希固定下来；执行中途有人激活新版本，不影响正在跑的批次。
- 界面只显示 SQL 的哈希和只读预览，**不提供编辑**。

---

## 7. 执行器规则（与 `etl_daily.py` 逐条对齐）

| 规则 | 旧程序位置 | DataEase 实现 |
|---|---|---|
| 去掉以 `--` 开头的整行，按 `;` 拆分，丢弃空语句 | `statements()` 82–84 | 原样实现。**不做**识别字符串字面量的"智能拆分"，以保证行为完全一致 |
| 占位符替换 | `refresh_sql()` 104–108 | 三个占位符原样替换 |
| 替换后全文含 `RENAME TABLE`，或仍残留 `__REQUIRED_`、`__SAFETY_SMOKE_PASSED__`，就拒绝执行 | 109–110 | 原样实现，在执行任何语句之前检查 |
| 含 `__STOP__` 的语句跳过 | 140–141 | 原样实现（refresh.sql 开头的门禁 SELECT 会因此被跳过，与旧程序一致） |
| 语句以 `RENAME` 开头时抛错 | 143–144 | 原样实现 |
| 事务外的语句逐条提交；`START TRANSACTION` 到 `COMMIT` 之间不提交 | 137–153 | 用 `autoCommit=false` 加显式 `commit()`，状态机与旧程序相同 |
| 出错时 ROLLBACK | 290–296 | 执行 `ROLLBACK` 语句，再调用 `connection.rollback()`，然后记 fail |
| 会话超时 | `prepare()` 130–134 | 四条 `SET SESSION ...=28800` |
| 窗口 | `window_for()` 68–71 | `window_end` 为触发时刻在 Asia/Shanghai 的日期，`window_start` 为它往前 35 天；用 `ZoneId.of("Asia/Shanghai")` 显式计算，不依赖 JVM 默认时区，也不依赖数据库的 `NOW()` |
| 依赖：上游在同一 `planned_at` 下不是 success，下游记 blocked | `upstream_ok()` 226–232 | 同样实现，并按 `kind` 隔离（测试运行只看 `refresh_test` 记录） |
| 认领：已存在的 success、fail、blocked 都跳过；唯一键冲突时重新查询 | `claim()` / `run_one()` 187–252 | 原样实现 |
| 跑完后再走一遍整条链，确认不会重复执行 | `run_slot()` 311–316 | 原样保留，作为自检 |
| 续跑：36 小时内 `status=running`、`trigger_type=schedule` 的批次接着跑完 | `resume_running()` 322–344 | 只以 `etl_run_log` 为准，额外加 `kind='refresh'` 条件；按 planned_at 分组后依次续跑 |

旧程序没有、本方案补充的规则【待定】：
- **手动触发记录的续跑**：旧程序没有手动触发。进程重启后，遗留的 `trigger_type=manual`、`status=running` 记录改为 `fail`，`note` 写"进程重启中断"，不续跑。
- **只跑单个下游任务**：按同一 `planned_at` 的规则，它的上游一定是空的，会被 blocked。建议的规则是：单任务手动运行时，如果上游最近一次**同 kind、同 `window_end`** 的运行是 success，就视为满足依赖，并在 `note` 里写明所依据的上游记录 id。否则一律 blocked。

GTID 相关：`enforce_gtid_consistency=ON` 时，MySQL 5.7 禁止 `CREATE TABLE ... SELECT`，也禁止在事务里创建临时表。这些写法要由 SQL 作者避免，DataEase 不改写；执行时报错就如实记 fail。阶段 2 的测试初始化脚本也必须遵守这一点（用 `CREATE TABLE ... LIKE` 再 `INSERT ... SELECT`）。

---

## 8. 调度、开关、锁与续跑

### 8.1 全局开关（持久化在元数据表，并绑定实例）

开关存放在 `company_etl_setting` 的 `scheduler_enabled` 和 `enabled_instance_id`。**真正生效**需要同时满足三个条件：
1. `scheduler_enabled = 1`；
2. `enabled_instance_id` 等于**当前实例的 ID**；
3. 环境变量 `COMPANY_BI_ETL_HARD_DISABLE` 不等于 `true`。这是运维层面的强制关闭，`compose.yaml` 默认不设置。

为什么要绑定实例：元数据库会随整机复制被拷贝，如果只存一个 `scheduler_enabled=1`，复制出来的新实例就会是"开"的状态。绑定实例后，新机器的实例 ID 不同，开关**自动被视为关闭**，界面会显示"开关属于另一个实例"，需要在确认旧实例停掉后重新打开。

实例 ID 的取法【待验证，首选方案】：
- 读取阿里云 ECS 元数据 `http://100.100.100.200/latest/meta-data/instance-id`。它在每台 ECS 上唯一，镜像复制后会变化。
- 读不到时（例如容器内不可达）一律视为"无实例 ID"，开关失效，界面明确提示。
- 10-28 想核实容器内能否访问元数据服务时，ECS 的 SSH 出现超时，所以这一点尚未验证。
- 如果容器内访问不了，备选方案是由部署脚本在宿主机上读取实例 ID，作为环境变量 `COMPANY_BI_INSTANCE_ID` 注入。这时 `.env` 文件也会被复制，所以 `install.sh` 每次都要在宿主机上重新读取，不能写死在 `.env` 里。

打开开关的操作：只有管理员能操作。确认框里必须输入一句确认语（例如"旧刷新程序已停止"），并记入审计日志。

### 8.2 Quartz 注册
- 触发器放在单独的分组 `COMPANY_ETL`，Job 是 `EtlQuartzJob`。Cron 表达式由 `daily_at` 生成（`0 MM HH * * ?`），用 `inTimeZone(Asia/Shanghai)`，并显式调用 `withMisfireHandlingInstructionDoNothing()`。
- **启动时**（`ApplicationRunner`，在 Quartz 启动之后运行）先读开关：
  - 不生效时，**删除** `COMPANY_ETL` 分组下所有持久化的 Job 和 Trigger。这满足"关着就不注册任何触发器"，同时把整机复制带过来的触发器清掉；
  - 生效时，按当前的 `daily_at` 新增或更新触发器。
- 开关切换、修改执行时间、启停整条链时，同步增删触发器。
- **触发时再检查一次**：`EtlQuartzJob` 执行时重新读取开关和实例 ID，不满足就直接返回并记一条日志。这样即使有遗留的触发器，也不会执行。
- 不修改现有的 `ScheduleManager`，新建一个独立的注册类，避免影响 DataEase 原有任务的 misfire 行为。

### 8.3 单实例锁
- 每个批次开始前，用入口 A 取一条**专用连接**（刷新专用数据源），执行 `SELECT GET_LOCK('companybi_etl_refresh', 0)`。拿不到就记一条日志后退出：不写 `etl_run_log`、不认领、也不阻塞，与旧程序的 `LOCK_HELD` 行为一致。
- 锁在这条连接上一直持有到批次结束，然后 `RELEASE_LOCK` 并关闭连接。连接断开时 MySQL 会自动释放锁。
- 锁在 RDS 上，所以对连同一个 RDS 的所有 DataEase 实例都有效，包括复制出来的新实例。
- 旧 Python 程序用的是文件锁，不参与 `GET_LOCK`，所以它和 DataEase 之间只靠 `uk_plan` 和人工停机步骤来防止重复写（第 15 节）。
- 测试运行使用同一把锁，避免和正式运行抢 RDS 资源。

### 8.4 续跑
- 启动完成且开关生效后，在锁的保护下，按第 7 节的规则从 `etl_run_log` 查出 36 小时内 `kind='refresh'`、`trigger_type='schedule'`、`status='running'` 的批次，按 `planned_at` 依次续跑。不读取 Quartz 的状态。

### 8.5 手动触发
| 类型 | 允许的条件 | 记录 |
|---|---|---|
| 正式包，单个任务或从某任务往下跑完整条链 | 开关**在本实例生效**。否则拒绝，原因是旧程序还在运行时，手动写正式表也算第二个写入方 | `kind=refresh`，`trigger_type=manual`，`planned_at` 为触发时刻 |
| 测试包 | 开关是否生效都可以；但旧程序运行期间，**02:30–05:00 禁止执行**【待定】，以免与 03:00 的旧程序在同一时间争用 RDS | `kind=refresh_test`，`trigger_type=manual` |
| 测试初始化脚本 | 只有管理员可以执行，需要二次确认，每个测试包执行一次 | 不写 `etl_run_log`，写审计日志 |

---

## 9. 验收（与 `etl_daily.py:155–172、264–277` 一致，参数来自清单）

每个任务执行前后各取一次指标：
- 总行数；
- 窗口外（`<window_start>` 之前）的行数和金额合计；
- 2026-07 的行数和金额；
- 窗口字段的最小值、最大值；
- 窗口内的行数。

以下任何一项不满足都记 fail：
1. 窗口外的行数和金额，前后相同；
2. 2026-07 的行数和金额，前后相同；
3. 2026-07 等于清单基线，金额误差不超过 0.01；
4. 窗口内行数大于 0；
5. 最小日期等于 `history_start`（2026-01-01），且最大日期小于 `window_end`。

说明：
- 金额字段名固定为清单中的 `money_field`（现在是 `actualMoney`）。
- 验收发生在事务提交**之后**，与旧程序相同。所以验收失败时，新数据**已经写入**，只是记为 fail，阻塞下游，等人工处理。这是旧程序本来的语义，本方案沿用，不在本期改变【需知悉】。

---

## 10. 界面（集成中心 → 数据刷新）

- **任务**：表格列出任务名、目标表、窗口字段、依赖、执行时间、启停开关、上次运行状态、开始和结束时间、耗时、行数、失败原因（读 `etl_run_log` 中同一 `etl_name`、`kind=refresh` 的最新一条）。操作有"只跑这个"和"从这里往下跑"。
- **运行记录**：按 kind（正式、测试）、任务、日期筛选，按 `planned_at` 分组显示批次。
- **SQL 版本**：上传、校验结果、激活、历史记录、只读预览（显示哈希和全文，不能编辑）。
- **设置**：执行时间、整条链启停、刷新数据源（只能从 DataEase 已有的数据源中选择）、全局开关（显示本实例 ID、开关属于哪个实例、生效状态和原因）。
- 权限：页面和接口只对管理员开放，复用 `CompanyAdminGuard`；所有修改都写审计日志。

---

## 11. 凭据与安全

- 刷新专用数据源和秉坤数据源的口令只在 DataEase 数据源界面录入，由 `core_datasource.configuration` 加密保存。
- **风险**【实测】：这份加密使用的 AES 密钥和 IV 是 `commons/utils/EncryptUtils.java` 里的常量，也就是 DataEase 上游公开源码里的默认值。拿到元数据库备份加上公开源码，就能解密出所有数据源口令。本期不改加密方式（改动会影响所有已有的数据源）；但需要做到：
  1. 元数据库备份按密钥同等级别保管；
  2. `qiweibi` 有写权限，风险比只读账号更高，建议 RDS 白名单只放行 DataEase 所在的主机；
  3. 后续可以按 `company_integration_connection` 的方式，改为用 `COMPANY_BI_TOKEN_SECRET` 派生的密钥重新加密（另立项）。
- 仓库的 `application-standalone.yml` 里有一组历史数据库地址和 root 口令的明文（镜像构建时会清理，但在 Git 历史里）。建议另行轮换，本期不处理。
- 执行器的日志和 `note` 只写任务名、语句序号、SQL 状态码和截断后的错误信息，不写 SQL 原文中的数据，也不写连接信息。

---

## 12. 飞书联动风险（需要在本仓库修一处）

`feishu-integration/src/etl-status.mjs` 第 210–226 行按 `etl_name` 取 `ORDER BY id DESC` 的最新一条记录，**不过滤 `kind`**。阶段 2 写入的 `kind='refresh_test'` 记录如果和正式任务同名，就会被当作正式结果，可能导致飞书放行旧图或误拦截。

方案：在本仓库把这段查询改为 `AND kind = 'refresh'`，作为一个单独的提交，并补测试。etl_migration 不需要改动。

另外，内置执行模式下这段代码读的是元数据库里的 `etl_run_log`（第 196–203 行），读不到 `qiwei_new_bi.etl_run_log`，所以目前关联了 ETL 的订阅会被一律拦截。这是已有问题，不在本期范围，记入未完成事项。

---

## 13. 时区

- 执行器所有时间计算都显式使用 `ZoneId.of("Asia/Shanghai")`；写库时用格式化好的字符串，不传 `Timestamp`，也不用 `NOW()`。
- `compose.yaml` 的默认 JVM 参数里已有 `-Duser.timezone=Asia/Shanghai`，但它可能被 `.env` 中的 `COMPANY_BI_JAVA_OPTS` 覆盖。所以：
  - (a) 在 `compose.yaml` 的 `app` 服务里加上 `TZ=Asia/Shanghai`；
  - (b) 启动时如果检测到 JVM 默认时区不是 Asia/Shanghai，就在界面和日志里告警，但不依赖它来计算窗口。
- Quartz 触发器显式设置 `inTimeZone(Asia/Shanghai)`。

---

## 14. 测试计划（阶段 1）

单元测试（JUnit 5，不连数据库）：
1. 占位符替换；任一占位符残留时拒绝；全文含 `RENAME TABLE` 时拒绝；语句以 `RENAME` 开头时拒绝；含 `__STOP__` 的语句被跳过；注释行被去掉；空语句被丢弃。另外用现有三份 refresh.sql 做快照测试，确认拆出来的语句与 Python `statements()` 的结果逐条一致。
2. 窗口计算：任意 `Clock` 下按 Asia/Shanghai 计算；跨月、跨年；JVM 默认时区设为 UTC 时结果不变。
3. 验收：每一项单独失败时都记 fail；金额误差恰好 0.01 和 0.011 两个边界。
4. 导入校验：哈希不符、依赖成环、缺占位符、测试包写正式表、事务内有 DDL。
5. 续跑决策：36 小时边界；只续跑 `schedule` 和 `refresh` 的记录；手动触发的 running 记录改为 fail。
6. 错过不补跑：Quartz 触发器的 misfire 策略是 `DO_NOTHING`；用 `RAMJobStore` 加可控时钟模拟停机跨过 03:00，确认重启后不会触发。

集成测试（用 MySQL 5.7.44 容器，开启 GTID 和 `enforce_gtid_consistency`，与生产一致）：
7. 事务中途出错后回滚：窗口外和窗口内的旧数据都不变，记录为 fail。
8. 依赖阻塞：上游 fail 时下游记 blocked，并且不执行 SQL。
9. 同一个 `planned_at` 只执行一次：连续触发两次、两个线程并发触发、手工预先插入一条 running 记录，三种情况都要验证。
10. 重启续跑：中途杀掉执行线程，重启后从 `etl_run_log` 续跑，最终每个任务只有一条 success 记录。
11. 单实例锁：两个执行器实例连同一个库，只有一个能拿到 `GET_LOCK`；锁连接被杀后可以重新获取。
12. 验收失败记 fail：故意让基线不一致，记为 fail，下游 blocked。
13. 开关：开关属于另一个实例时，启动后触发器被删除，触发时也不会执行。
14. 连接来源：断言执行器只通过 `CalciteProvider.getConnection` 取连接（用 mock 验证），代码里没有 `DriverManager.getConnection`（静态扫描）。

本机环境要求：Docker（运行 MySQL 5.7 容器）、JDK 21、Maven。core-backend 默认 `skipTests=true`，测试时显式加 `-DskipTests=false`。

---

## 15. 阶段 2：测试运行（部署到临时 ECS）

前提：etl_migration 提供测试包，包含：
- `init/init_dataease_test.sql`：从正式表整表复制出三张 `<正式表>_dataease_test`；
- 三份已改写好目标表、上游表和暂存表名的 refresh.sql；
- `run_kind=refresh_test`。

测试表名（请 Leo 知悉）：
- `qiwei_new_bi.销售订单数据_线下_dataease_test`
- `qiwei_new_bi.全渠道_销售_dataease_test`
- `qiwei_new_bi.产品分渠道销售_dataease_test`
- 暂存表按 etl_migration 的改写结果，预计为 `<测试表>_refresh_stage`。

步骤：
1. 构建镜像（基线按第 20 节的决定），用 `scripts/upgrade.sh` 部署到临时 ECS；全局开关保持关闭。
2. Leo 在界面新建「qiwei_new_bi 刷新专用」数据源（账号 `qiweibi`），并在设置中选中它。
3. 只读预检：查 `information_schema` 中三张正式表的数据量，评估 RDS 剩余空间（RDS 磁盘余量需要在阿里云控制台看，SQL 查不到）。
4. 导入测试包，激活；执行初始化脚本（管理员二次确认）。
5. 在当天正式 03:00 那次运行成功之后、源数据变化较少的时段，手动触发整条测试链（避开 02:30–05:00）。
6. 比对同一天的正式表和测试表：
   - 窗口内的行数、`actualMoney` 合计、`orderQuantity` 合计；
   - 2026-07 切片的行数和金额；
   - 窗口外的行数和金额。

   比对用只读 SQL，结果写入报告。窗口内可能因为 03:00 之后源库数据变化而出现差异，出现时需要回到源库做只读核对，确认差异来自源数据变化。
7. 确认飞书的 `kind` 过滤修复已经上线，避免测试记录影响飞书。
8. 测试结束后，测试表是否保留由 Leo 决定；DataEase 不会自动删除。

---

## 16. 阶段 3：切换（需要 Leo 确认后才执行）

1. etl_migration 在 39.103.208.62 上执行 `systemctl stop qiwei-etl-daily` 和 `systemctl disable qiwei-etl-daily`（由他们操作，DataEase 一侧不登录那台机器）。
2. 确认已经停止：
   - (a) etl_migration 给出 `systemctl is-active` 的输出；
   - (b) DataEase 只读确认 `etl_run_log` 在停机之后没有新的 `trigger_type=schedule` 写入。
3. 导入正式包并激活（hash 和来源 commit 记入审计日志）。
4. 打开全局开关（输入确认语）；确认 `COMPANY_ETL` 分组下的触发器已注册，下次触发时间为次日 03:00。
5. 次日检查：
   - 三条 `kind=refresh`、`trigger_type=schedule`、`planned_at=03:00` 的记录都是 success；
   - 耗时与旧程序同期相当；
   - 验收项全部通过；
   - 飞书侧状态正常。
6. 回退：关闭 DataEase 开关（触发器立即删除）→ etl_migration 重新启用旧服务。顺序不能反过来。

---

## 17. 阶段 4：秉坤接入（按 A → B 的顺序评估）

前提：Leo 已在 DataEase 中配置好秉坤 SQL Server 数据源，直连或 SSH 均可，并且"校验连接"通过。

### 17.1 方案 A：不落库，用跨源数据集
- 能力【推断，需要实测】：社区版核心代码就支持跨源数据集，代码里由 `isCross` 分支处理；引擎是 Apache Calcite，每个数据源对应一个 `JdbcSchema`，连接走入口 B 的连接池（同样支持 SSH）。
- 下推：列裁剪和单表过滤由 Calcite JDBC adapter 下推到各自的数据库（SQL Server 的方言用 `MssqlSqlDialect`，版本 11）；**跨库关联在 DataEase 的 JVM 内执行**（哈希连接）。也就是说，`IF_Sale` 过滤后约 2.65 万行、`IF_Employee` 约 558 行会被拉进 DataEase 内存，然后与左表做关联。
- 限制：查询超时取数据源的 `queryTimeout`（默认 30 秒）；结果受数据集和图表的行数上限约束；JVM 堆 1g、容器 2g。
- 实测项目：左表取一个月的 `销售订单数据_线下`，左连接两张秉坤表。记录：
  - 冷查询和热查询的耗时；
  - 容器内存峰值（`docker stats` 每秒采样）和 JVM 堆变化；
  - 秉坤侧实际执行的 SQL（确认过滤条件是否下推，`IF_Sale` 有没有被全表 182 万行拉回来）；
  - 关联命中率。
- 已知判断要点：迁移后 39.103.208.62 只剩约 2.8G 可用，跨库关联发生在 JVM 内，会直接加重内存压力，这一点会作为 A/B 取舍的重要依据。

### 17.2 方案 B：只有 A 不可行时才用，"SQL Server → MySQL 全量快照"任务类型
- 写入 `qiwei_new_bi.秉坤BA信息表` 和 `qiwei_new_bi.秉坤会员订单`。
- 只取规定的字段和筛选条件：
  - `IF_Employee`：`DepartCode IN (36 个编码)`，不去重；
  - `IF_Sale`：`RelateBillCode IS NOT NULL AND RelateBillCode <> ''`，不加品牌条件，不加 DISTINCT。
- 按主键分批（keyset 分页）读写，每批约 2000 行：`IF_Employee` 按 `IdentityID`，`IF_Sale` 按 `(BillCode, BrandCode)`。读取连接走入口 A（秉坤数据源），写入连接走入口 A（刷新专用数据源）。
- 编码列一律建成 `VARCHAR`，长度与源表一致；所有编码列（包括 `RelateBillCode`）都用 `setString` 写入，不经过数值转换。
- 先写影子表 `<表>_new`，校验通过后执行 `RENAME TABLE 正式表 TO 正式表_old, 正式表_new TO 正式表` 完成原子切换，再删除 `_old`。快照任务属于新任务类型，不受 refresh.sql 的 RENAME 禁令约束；这一点需要 Leo 确认【待定】。
- 校验项：
  - 行数等于源表计数；
  - 主键唯一且没有 NULL；
  - `RelateBillCode` 没有空值；
  - 前导零的行数与源一致；
  - 抽样回读一致。
- 失败时只删除影子表，保留旧的正式表。
- 运行记录写入 `etl_run_log`，`kind='snapshot'`；它作为「798日报」的上游依赖，必须先于后者执行。
- GTID 约束：影子表用 `CREATE TABLE` 再分批 `INSERT`，不用 `CREATE ... SELECT`。

---

## 18. 迁移到 39.103.208.62

### 18.1 与机器绑定的配置

| 项 | 位置 | 迁移时的处理 |
|---|---|---|
| 主机 IP 和端口绑定 | `.env`：`COMPANY_BI_BIND_ADDR`、`COMPANY_BI_PORT` | 检查有没有与观远的端口冲突 |
| 浏览器允许的访问源 | `.env`：`COMPANY_BI_ORIGIN_LIST` | 按新的访问方式修改 |
| 数据目录 | `.env`：`COMPANY_BI_HOME` | 新机器的磁盘路径 |
| 内存预算 | `.env`：`COMPANY_BI_APP_MEM_LIMIT=2g`、`COMPANY_BI_MYSQL_MEM_LIMIT=768m`、JVM 堆 1g | **合计约 2.8G，与那台机器可用的约 2.8G 相当，没有任何余量**。需要先评估、压缩预算，或者 CEO 和 Leo 重新考虑迁移目标机器【待定，属于迁移本身的风险】 |
| 时区 | JVM 参数和 `TZ` | 保持 Asia/Shanghai |
| 业务数据源地址 | `core_datasource`（加密） | 张北 RDS 和秉坤 RDS 的白名单要放行新机器（etl 侧确认旧程序本来就在那台机器上跑，张北 RDS 应已放行）；SSH 隧道配置按新网络重选 |
| Quartz 触发器 | 元数据库 `QRTZ_*` | 会随元数据库一起复制。由实例绑定的开关保证不会执行（第 8 节） |
| ETL 全局开关 | `company_etl_setting.enabled_instance_id` | 换了机器后自动视为关闭 |
| 单实例锁 | RDS 上的 `GET_LOCK` | 与机器无关，新旧实例会互斥 |
| 加密密钥 | `COMPANY_BI_TOKEN_SECRET`（集成连接）；数据源用源码常量 | `.env` 必须原样迁移，否则飞书等集成凭据无法解密 |
| 飞书执行器 | 镜像内置，带租约锁 | 新旧实例只能有一个启用飞书连接 |

### 18.2 切换步骤
1. 在旧实例（临时 ECS）上备份元数据库（`scripts/backup-db.sh`）和 `.env`。
2. 在新机器上装载同一版本的镜像，恢复元数据库和 `.env`，然后启动。开关属于旧实例，所以新实例启动后会删除 `COMPANY_ETL` 分组的触发器，不会执行刷新。
3. 在新实例上核对数据源连接、仪表板和刷新页面，确认开关显示"属于另一实例，未生效"。
4. **关闭旧实例的开关**，然后停掉旧实例（或至少停掉 app 容器）。
5. 在新实例上打开开关（输入确认语）。
6. 次日检查 03:00 那次运行。

### 18.3 自定义代码怎样随迁移带走，以及回滚
- 自定义代码全部在镜像里（镜像 tag 对应 Git commit），表结构由启动时的初始化逻辑建立，**不需要在宿主机上额外部署任何东西**。迁移就是"同一个镜像 tar 加元数据库备份加 `.env`"。
- 回滚到不含刷新功能的旧镜像：先关闭开关，再执行 `scripts/rollback.sh --to <旧版本>`。新增的表会被保留（旧代码不读它们），`QRTZ_*` 里 `COMPANY_ETL` 分组的残留触发器指向的类在旧镜像里不存在。**这里有一个需要在阶段 1 处理的风险**：旧版本 Quartz 加载时会因为找不到类而报错。处理方法是回滚前先关开关（这会删除触发器），`rollback.md` 里要写明这一步。
- 刷新职责的回滚：见第 16 节第 6 步。

---

## 19. 工期与风险

| 工作 | 预计 |
|---|---|
| 执行器（脚本、语句执行、验收、运行记录）加单元测试 | 3 天 |
| 调度（Quartz 注册、开关和实例绑定、锁、续跑）加测试 | 2 天 |
| 导入、版本、校验 | 1.5 天 |
| 前端页面 | 2 天 |
| MySQL 5.7 GTID 集成测试 | 1 天 |
| 飞书 `kind` 过滤修复 | 0.5 天 |
| 阶段 2 部署、测试和比对 | 1–1.5 天 |
| **合计（阶段 1–2）** | **约 11 个工作日，误差 ±30%** |
| 阶段 4，方案 A 实测 | 1 天（前提：Leo 已配置好秉坤数据源） |
| 阶段 4，方案 B 实现 | 另加约 3 天 |

主要风险：

| 风险 | 影响 | 应对 |
|---|---|---|
| 容器内拿不到 ECS 实例 ID | 开关的实例绑定无法使用 | 用宿主机注入的备选方案；ECS 恢复后第一时间验证 |
| 旧程序和 DataEase 在手动触发上重叠 | 同一张表有两个写入方 | 正式包的手动触发需要开关生效；切换步骤严格按顺序执行 |
| `DataSourceManage.getDs` 在调度线程中被权限拦截 | 定时任务取不到连接 | 阶段 1 先做技术验证；必要时用内部的系统身份 |
| 刷新 SQL 运行时间长、RDS 负载高 | 影响仪表板查询 | 沿用 03:00 执行；记录耗时，与旧程序对比 |
| 验收在提交后进行 | 验收失败时新数据已写入 | 沿用旧语义，并在界面上高亮提示需要人工处理 |
| 迁移目标机器内存不够 | 整个 DataEase 不稳定 | 迁移前单独评估，必要时上报 CEO |
| 本地 HEAD 包含尚未上线的改动 | 阶段 2 部署会带上飞书运行时和数据集预览改版 | 第 20 节第 1 项决定基线 |

---

## 20. 待 Leo 拍板

1. **代码基线**：刷新功能基于哪个提交开发并部署？
   - 选项一：基于 `3598e462`（临时 ECS 现行版本），只带刷新功能，风险最小；
   - 选项二：基于当前 HEAD `2afb1b4d3e`，会一起带上飞书运行时和数据集预览改版。
   - 无论选哪个，都在独立的 worktree 和新分支 `feature/etl-refresh` 上开发。
2. **SQL 包格式**：同意第 6.2 节的清单格式后，由 etl_migration 按此格式生成正式包和测试包。
3. **导入时的事务内 DDL 检查**（第 6.3 节第 5 条）：要不要启用？
4. **单任务手动运行时的上游判定规则**（第 7 节）。
5. **手动触发的 running 记录**：重启后改为 fail，不续跑（第 7 节）。
6. **测试运行禁止时段** 02:30–05:00（第 8.5 节）。
7. **实例 ID 的取法**：首选 ECS 元数据，备选由宿主机注入（第 8.1 节）。
8. **飞书 `kind` 过滤修复**放进本期（第 12 节）。
9. **方案 B 的快照任务允许用 RENAME 原子切换**（第 17.2 节）。
10. **迁移目标机器的内存问题**是否需要上报 CEO（第 18.1 节）。

---

## 21. 提交记录与计划

已完成（10-28，按 Leo 的要求提交了工作区里原有的改动，未推送）：
- `aa05851c1b` feat(dataset)：只读数据集预览与预览导出（后端、SDK、单元测试、`.gitignore` 放行规则）
- `67ded0a76e` feat(dataset-ui)：数据集预览、字段列表与筛选器改版（前端）
- `2afb1b4d3e` docs(companybi)：数据集改版设计稿与预览方案更新

阶段 1 计划的提交（在 `feature/etl-refresh` 上）：
1. `feat(etl): refresh script parser, statement executor and acceptance`
2. `feat(etl): run log repository, chain runner, lock and resume`
3. `feat(etl): quartz registration with instance-bound global switch`
4. `feat(etl): package import, validation and versioning`
5. `feat(etl-ui): integration center refresh page`
6. `fix(feishu): only count kind=refresh runs for freshness`
7. `chore(deploy): TZ env, ETL hard-disable switch, docs and .gitignore allowlist`
8. `test(etl): MySQL 5.7 GTID integration tests`

---

## 22. 尚未完成或需要补做的核对
- 临时 ECS 的 SSH 在 10-28 出现超时，以下两项需要恢复后补做：容器内能否访问 ECS 元数据服务；元数据库 `QRTZ_*` 的实际表和行数。
- `etl_run_log` 的 `SHOW CREATE TABLE` 由 etl 侧核对过，DataEase 一侧在阶段 2 部署后，再用刷新专用数据源做一次只读复核。
- 旧程序每次运行的历史耗时（从 `etl_run_log` 只读查询），作为切换后的对比基准。
