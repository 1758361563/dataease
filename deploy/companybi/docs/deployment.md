# Company BI 部署手册

> 适用对象：构建机（有 Docker）+ 一台只有 Docker/Compose/bash 的服务器（阿里云聚石塔 ECS，与观远 BI 同机）。
> 约定：命令里的 `<v>` 是版本号，形如 `2.10.25-20261015-a1b2c3d4`，由 `build.sh` 生成。

## 1. 架构与隔离

| 对象 | 名称 | 说明 |
|---|---|---|
| 应用容器 | `companybi-app` | Spring Boot jar（含前端），容器内 8100，宿主机仅 `127.0.0.1:30005` |
| 元数据库 | `companybi-mysql` | `mysql:8.4.5`，**专用**，不发布端口，仅 `companybi-net` 内可达 |
| 网络 | `companybi-net` | 独立 bridge；不加入观远任何网络 |
| 数据 | `${COMPANY_BI_HOME}/…` | 全部是 **bind mount**，没有 Docker 命名卷（没有东西可被 volume prune 误删） |
| 项目名 | `COMPANY_BI_PROJECT`（默认 `companybi`） | 测试栈改成别的名字即可与生产并存 |

元数据库只存 DataEase 自身的配置、权限、看板；**业务数据（如 `qiwei_new_bi`）作为"数据源"后续再接**，建议只读账号，不在本次部署范围。

### 持久化布局（`COMPANY_BI_HOME`，默认 `/data/companybi`）

| 路径 | 内容 | 丢失后果 |
|---|---|---|
| `mysql/data` | MySQL 数据目录 | 元数据全丢 → 靠 `backups/` 恢复 |
| `mysql/conf/companybi.cnf` | `mysql/my.cnf` 的私有副本（`install.sh` 以 644 生成；MySQL 会**静默忽略 world-writable 的配置文件**，所以不直接挂载 checkout 里的文件）。`upgrade.sh` 不会刷新它（只在文件缺失时补建）；要改 MySQL 参数请手工替换该文件并重启 mysql 容器 | MySQL 回落到默认参数（内存超预算风险） |
| `app/data` | 上传的静态资源、地图、自定义字体、**`company-bi-token.secret`（若未用环境变量）** | 图片/地图丢失 |
| `app/conf` | `share-secret.json` 等配置 | 已分享链接失效 |
| `app/cache` / `app/logs` | 缓存 / 日志 | 可重建 |
| `backups/` | 升级前自动备份、手动/定时备份、`.env` 副本 | 失去回滚保险 |
| `releases/` | `version.env`、`state.env`、`history.tsv`、各版本 `RELEASE/SCHEMA/COMPAT` | 失去"当前是哪个版本"的记录 |
| `images/` | 预留：可放传输过来的 tar | — |

`.env` 里的 `COMPANY_BI_TOKEN_SECRET` **必须长期稳定**（改了所有人需重新登录），并与 `.env` 一起异地备份。

## 2. 前置条件

- 构建机：Docker（BuildKit）、Git、Git Bash 或 Linux shell、能访问 npm / Maven Central / fit2cloud 仓库（国内网络可用 `--npm-registry`）。
- 塔内：Docker ≥ 24（能 `docker load` 构建机导出的 tar）、`docker compose` v2 插件、bash ≥ 4.2、`flock`、`curl` 或 `wget`、`gzip`、`sha256sum`。**不需要** Git / Maven / Node / JDK。
- 塔内空闲资源（安装/升级脚本会检查，不足则拒绝）：内存 ≥ 应用上限 + MySQL 上限 + 512 MiB（默认 2 GiB + 768 MiB + 512 MiB），磁盘 ≥ 5 GiB。**不足时不得通过动观远来腾资源**，应下调 `COMPANY_BI_*_MEM_LIMIT` 或换机器。

## 3. 构建（构建机）

```bash
cd <仓库根>                                     # 一个干净的 commit
bash deploy/companybi/scripts/build.sh
```

- 构建上下文来自 `git archive HEAD`：**只含已提交内容**，本地未提交/被忽略的文件不会混入。工作区不干净则拒绝（`--allow-dirty` 仅限调试，镜像标记 `-dirty`，安装/升级脚本会拒绝它）。
- 全部在 Docker 内完成：`npm ci && npm run build:base` → `mvn install`（sdk）→ `mvn -P standalone package` → 内容校验 → 运行镜像。构建机不需要装 Maven/Node。
- 版本号 = `<dataease.version>-<提交日期UTC>-<commit前8位>`；镜像 `companybi:<v>`；**没有 `latest`**。
- "可追溯"指：镜像里的 `RELEASE` 文件与标签记录了 commit、分支、dirty 标记、构建时间，且构建输入只来自该 commit。**不承诺逐位可复现**：Docker 层缓存命中时，同一份源码会得到字节相同的 jar（实测 V1 与 RC 的 `jar_sha256` 相同）；但改了源码或加 `--no-cache` 重建后是否仍逐位相同，没有验证。因此以"镜像 ID + tar 的 SHA256"作为一次具体发布物的身份，而不是只看 commit。
- 构建期强制检查（任一失败则构建失败）：jar 含前端 `index.html`/`panel.html` 及全部 dist 文件、公司定制类、全部 `db/company/*.sql` 与 Flyway 迁移；jar 内不含 `application-standalone.yml` 历史上硬编码的远程数据源地址与密码（对 jar 内所有类/资源做字面量扫描）；`application-standalone.yml` 中数据源已被替换为必填占位符。
- 产物：镜像 + `deploy/companybi/dist/<v>/companybi_<v>.manifest`（版本、commit、分支、dirty、构建时间、镜像 ID、jar SHA256、schema 指纹、与上一版 schema 是否变化）。
- `--previous-manifest <旧 manifest>`：对比上一版，写入 `schema_changed_vs_previous`。

镜像内无数据库：没有 `COMPANY_BI_DB_URL/USER/PASSWORD` 时容器直接退出（退出码 64），**绝不会悄悄回退到 H2 或旧远程库**。

## 4. 导出与传输

```bash
bash deploy/companybi/scripts/export-image.sh --version <v> --with-mysql
# dist/<v>/ 下：
#   companybi_<v>_linux-amd64.tar(.sha256)   镜像
#   mysql_8.4.5_linux-amd64.tar(.sha256)     MySQL 镜像（塔内不要在线拉取）
#   companybi_deploy_<v>.tar.gz(.sha256)     compose + 脚本 + 文档
#   release_<v>.txt                          发布记录（commit、构建时间、镜像 ID、各文件 SHA256、dirty 标记、schema 指纹）
scp dist/<v>/* <user>@<塔内机器>:/tmp/companybi-<v>/
```

在塔内：`cd /tmp/companybi-<v> && sha256sum -c *.sha256`，通过后再继续。`load_image_tar` 在加载前也会自动校验同名 `.sha256`，不一致即拒绝加载。

> 未经负责人明确要求，本次交付不连接塔内服务器；以上 scp/ssh 步骤由负责人在窗口期执行。

## 5. 首次安装（塔内）

```bash
mkdir -p /opt/companybi && tar -xzf companybi_deploy_<v>.tar.gz -C /opt && cd /opt/companybi
scripts/install.sh --init-env        # 生成 .env（权限 600；随机 DB 密码与 token secret）
vi .env                              # 填 COMPANY_BI_BOOTSTRAP_PASSWORD(>=12位)，确认 COMPANY_BI_HOME、端口、内存上限
scripts/install.sh --version <v> \
    --mysql-image-tar /tmp/companybi-<v>/mysql_8.4.5_linux-amd64.tar \
    --image-tar       /tmp/companybi-<v>/companybi_<v>_linux-amd64.tar
```

`install.sh` 的保护：已存在同名容器/网络、`mysql/data` 非空、端口被占、状态已记录版本、密钥为空/过短/含非法字符、镜像不是 `linux/amd64`、镜像标签与版本不符、镜像是 dirty 构建、内存/磁盘不足——任一成立即拒绝，且不会删除任何东西。

启动顺序：MySQL（等 healthy）→ 应用（首次启动会跑 Flyway + 公司表初始化，可能数分钟）→ `health-check.sh`。成功后写入 `releases/state.env` 与 `history.tsv`。

访问（仅回环）：`ssh -L 30005:127.0.0.1:30005 <user>@<塔内>`，浏览器开 `http://localhost:30005`，账号 `admin`，密码为 `COMPANY_BI_BOOTSTRAP_PASSWORD`（只在 `company_bi_user.id=1` 不存在时使用，之后改密码走系统内功能；可从 `.env` 移除该值）。若换了访问域名/端口，同步改 `COMPANY_BI_ORIGIN_LIST`。

## 6. 升级流程（六步）

```bash
scripts/upgrade.sh --version <v2> --image-tar companybi_<v2>_linux-amd64.tar --dry-run   # 先看评估，不改任何东西
scripts/upgrade.sh --version <v2> --image-tar companybi_<v2>_linux-amd64.tar              # 正式
```

| 步 | 做什么 | 失败时 |
|---|---|---|
| 1 预检 | 记录版本 = 实际运行版本；当前版本健康；无 `PENDING_MANUAL`；内存/磁盘/锁 | 拒绝，不改任何东西 |
| 2 镜像与 Schema | 校验 SHA256 → `docker load` → 校验平台/标签/非 dirty；比较新旧 **schema 指纹**，分类 `NONE` / `DECLARED_COMPATIBLE` / `UNCONFIRMED`，并扫描破坏性关键字（DROP/TRUNCATE/DELETE/RENAME/MODIFY…） | `UNCONFIRMED` 或含破坏性关键字时，必须显式 `--accept-schema-risk`，否则拒绝 |
| 3 备份 | `mysqldump --single-transaction` + `.env` + `conf`，**先于停机** | 备份失败 → 终止，未改动 |
| 4 切换 | 只停 `companybi-app`，只重建 app 服务；MySQL 不重启 | 脚本异常中断 → 标记 `PENDING_MANUAL`，提示查 `status.sh` |
| 5 健康检查 | 容器 healthy、UI 200、依赖元数据库的公开 API `code:0`、管理员行存在、无失败的 Flyway 迁移、日志无致命错误、运行版本 = 目标版本 | 进入第 6 步失败分支 |
| 6 记录 / 回滚 | 成功：写 `state.env`、`history.tsv`，**旧镜像保留**；失败：见 [rollback.md](rollback.md) | 只尝试一次；不来回切换 |

**在 `tmux`/`screen` 里或用 `nohup` 运行升级与回滚。** SSH 断开会让脚本在"应用已停、新版本未验证"的中间态被打断；脚本的 EXIT 钩子会尽力把状态标成 `PENDING_MANUAL`，但被 `kill -9` 时无法执行。此时按 [rollback.md](rollback.md) 2.5 处理。（该场景未在本地测试中实际演练，见交付报告"未完成事项"。）

升级**不会**自动删除旧镜像、备份或数据。清理旧镜像由人工、按精确 tag 执行（`docker rmi companybi:<旧版本>`），且至少保留当前 + 上一版。

### Schema 变更怎么处理

- 镜像内 `SCHEMA` 文件记录每个 Flyway 迁移文件、每个公司 `db/company/*.sql`、初始化器源码的哈希，汇总为 `schema.fingerprint`。
- 两个版本指纹相同 → 数据库结构无变化，镜像回滚天然安全。
- 指纹不同 → 新版本若在 `schema-compat.txt` 中**声明**了旧版指纹（人工验证"旧版本能在新结构上运行"之后才可写），才被视为 `DECLARED_COMPATIBLE`；否则 `UNCONFIRMED`。
- 本项目的公司表初始化是**只加不删**的幂等脚本（`CREATE TABLE IF NOT EXISTS` + 带检查的 `ALTER`），旧版通常能在新结构上启动，但这是**推测**而非保证；是否写入 `schema-compat.txt` 由发布负责人对照实际迁移内容决定。

## 7. 备份

```bash
scripts/backup-db.sh                                  # 手动
15 3 * * *  /opt/companybi/scripts/backup-db.sh --label scheduled --keep 14     # crontab 示例
```

备份是 gzip 的逻辑导出，权限 600，目录权限 700，**不进 Git**。同盘备份不防磁盘损坏，请定期复制到机外。升级前/回滚前自动生成的备份（`pre-upgrade-*`、`pre-rollback-*`）不会被 `--keep` 清理。

## 8. 资源预算（初始值，待塔内实测校准）

| 组件 | 上限 | JVM/配置 |
|---|---|---|
| 应用 | 2 GiB / 2 CPU / 1024 pids | `-Xms512m -Xmx1g -XX:MaxMetaspaceSize=256m -XX:+ExitOnOutOfMemoryError` |
| MySQL | 768 MiB / 1 CPU | `innodb_buffer_pool_size=256M`，`max_connections=200`，`performance_schema=OFF` |
| 日志 | 每容器 20 MiB × 5 | 应用文件日志在 `app/logs`，需要人工留意磁盘 |

`status.sh` 显示当前内存占用。以塔内真实空闲内存为准调整，**不得通过动观远来腾资源**。

## 9. 飞书

本阶段 `COMPANY_FEISHU_DRY_RUN=true` 写死在 `compose.yaml`（不读 `.env`），无飞书凭据，不部署 Node/Playwright 截图服务，不创建正式定时任务。开启真实推送是一次需要评审的 `compose.yaml` 变更，不是配置项。

## 10. 禁止事项清单

`docker compose down`、`docker system prune`、`docker volume prune`、`docker network prune`、任何不带项目名的批量 stop/rm、修改 `/etc/docker`、重启 dockerd、使用 DataEase 官方一键安装脚本、在塔内编译、`latest` 标签、用官方 DataEase 镜像冒充定制版。`tests/static-audit.sh` 会静态检查脚本与 compose 是否违反这些规则。

## 11. 本地验证（不连接任何真实环境）

```bash
bash deploy/companybi/tests/static-audit.sh                                   # 测试 F：脚本/compose 越界静态审计，不运行 docker
bash deploy/companybi/tests/local-e2e.sh --v1 <旧版> --v2 <新版> --dirty-ver <脏构建版本>   # 测试 T/A–F，需要 WSL/Linux + docker
```

`local-e2e.sh` 使用独立项目名 `cbitest`、端口 `127.0.0.1:30105`、数据目录 `~/cbitest`，只按名字清理自己创建的对象，并对"其他容器/网络/卷/镜像"做前后快照比较。Schema 变化与启动失败场景用**派生镜像**模拟（在真实 v2 镜像上改 SCHEMA/COMPAT 清单或把入口改成立即失败），这些 schema 差异是**模拟**的，不是真实迁移。

## 12. 已知限制

- 镜像以 root 运行（继承基础镜像），目录权限靠宿主机目录 + `no-new-privileges`；后续可改为非 root 用户。
- 运行时基础镜像 `registry.cn-qingdao.aliyuncs.com/dataease/alpine-openjdk21-jre` 来自 DataEase 官方，JRE 版本较旧，应纳入后续安全更新计划。
- 单机单实例：升级期间有数分钟中断；不含蓝绿/多副本/CI-CD。
- 镜像没有 registry digest；镜像身份以 image ID（`docker image inspect`）+ tar 的 SHA256 为准。
- 飞书服务、业务数据源、反向代理/TLS 不在本次范围。
