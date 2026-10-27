# Company BI — 部署与版本维护

Company BI 是公司定制的 DataEase（仓库 `1758361563/dataease`，分支 `company/bi` 一脉）。
本目录是它的**正式部署与版本更新机制**，不仅是一个 Docker 镜像：

| 能力 | 在哪 |
|---|---|
| 可复现、可追溯到 Git commit 的镜像构建 | `scripts/build.sh`、`Dockerfile` |
| 镜像导出 + SHA256 + 部署包（供无构建工具的塔内机器使用） | `scripts/export-image.sh` |
| 独立 Compose 栈（应用 + 专用 MySQL 元数据库 + 持久化目录） | `compose.yaml`、`mysql/my.cnf`、`.env.example` |
| 首次安装 / 升级 / 回滚 / 备份 / 健康检查 / 状态 | `scripts/{install,upgrade,rollback,backup-db,health-check,status}.sh` |
| Schema 变更感知（决定回滚是否安全） | `schema-compat.txt`、镜像内 `SCHEMA` / `COMPAT` |
| 本地隔离测试 | `tests/`：`static-audit.sh`（静态审计）、`local-e2e.sh`（A–F 全量）、`release-verify.sh`（**正式发布文件**的冒烟 + 数据库真实恢复） |
| 服务器检查方案（尚未执行） | [docs/plan-A-personal-server-test.md](docs/plan-A-personal-server-test.md)（个人服务器隔离部署）、[docs/plan-B-tower-readonly-check.md](docs/plan-B-tower-readonly-check.md)（塔内只读检查）+ `scripts/tower-readonly-check.sh` |

文档：[docs/release-process.md](docs/release-process.md)（**怎么基于 `company/bi` 发布**）、[docs/deployment.md](docs/deployment.md)（完整流程）、[docs/rollback.md](docs/rollback.md)（回滚与故障恢复）。

## 一图看懂

```
 构建机(有 Docker)                              塔内 ECS(只需 docker + compose + bash)
 ───────────────────                            ───────────────────────────────────────
 git commit (干净)                              /data/companybi/            ← COMPANY_BI_HOME
   │ scripts/build.sh                             ├─ mysql/data             元数据库
   ▼                                              ├─ app/{data,conf,cache,logs}
 companybi:<版本>  ── export-image.sh ──► tar     ├─ backups/               备份(不进 Git)
   │            (+sha256 +部署包)   scp ──►       └─ releases/              版本状态/历史
   └─ manifest(commit,时间,镜像ID,schema指纹)    docker load → install.sh / upgrade.sh
                                                   容器: companybi-app (127.0.0.1:30005)
                                                         companybi-mysql (无对外端口)
                                                   网络: companybi-net   ← 与观远完全隔离
```

## 命令速查

构建机（Git Bash / Linux，仓库根目录）：

```bash
bash deploy/companybi/scripts/build.sh                       # 必须在干净 commit 上；调试用 --allow-dirty
bash deploy/companybi/scripts/export-image.sh --version <v> --with-mysql
```

塔内机器（解压部署包后，在 `companybi/` 目录）：

```bash
scripts/install.sh --init-env                                # 首次：生成 .env（随机密码），再手填 BOOTSTRAP 密码
scripts/install.sh --version <v> --mysql-image-tar mysql_*.tar --image-tar companybi_<v>_linux-amd64.tar
scripts/upgrade.sh --version <v2> --image-tar companybi_<v2>_linux-amd64.tar [--dry-run]
scripts/rollback.sh [--to <v>] [--restore-db <backup.sql.gz>]
scripts/backup-db.sh [--label scheduled --keep 14]
scripts/health-check.sh [--wait 300]
scripts/status.sh
```

## 不可违反的边界（脚本已内置，勿绕过）

- 只操作名为 `<project>-app` / `<project>-mysql` / `<project>-net` 的对象；不使用 `docker compose down`、`prune`、全量清理；不改宿主机 Docker 全局配置；不触碰观远任何进程/容器/网络/卷。
- 真实 `.env`、备份、镜像 tar、SQL 导出**不进 Git**；DB 密码不进镜像、不进日志。
- 飞书执行器随 BI 镜像交付并由后端托管；首次安装未配置应用连接且没有订阅，不会执行推送。初始管理员在“集成中心 → 应用连接”配置凭据及连接启停状态，`.env` 不放飞书应用凭据。见 [飞书运行与迁移说明](docs/feishu-integrated-runtime.md)。
- 无法证明数据库 Schema 兼容时，**不做**自动镜像回滚、**不做**自动 DB 恢复，只给人工步骤。
- 不在塔内编译 Java / Vue；塔内不需要 Git / Maven / Node。
