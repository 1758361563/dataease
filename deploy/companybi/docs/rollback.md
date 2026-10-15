# Company BI 回滚与故障恢复

核心原则：**镜像可以秒回滚，数据库不行。** 所以回滚是否"安全"取决于数据库 Schema 是否被新版本改过，而不是镜像还在不在。
脚本只在"能证明安全"时才自动回滚镜像；证明不了就停下来，保留备份，打印人工步骤。**不会**自动恢复数据库，也**不会**在新旧版本之间来回切换。

## 1. 决策表

| 新旧 schema 关系 | 判定 | 升级失败时 | 事后手动 `rollback.sh` |
|---|---|---|---|
| 指纹相同 | `NONE` | **自动**回滚镜像（一次） | 允许（仅镜像） |
| 指纹不同，新版本在 `schema-compat.txt` 声明兼容旧指纹，且新增/变更的 SQL 无破坏性关键字 | `DECLARED_COMPATIBLE` | **自动**回滚镜像（一次） | 允许（仅镜像） |
| 指纹不同，未声明兼容 | `UNCONFIRMED` | **不自动回滚**：停掉新应用，保留备份，打印人工步骤 | **拒绝**；只能 `--restore-db` |
| 指纹不同，含破坏性关键字（DROP/TRUNCATE/DELETE/RENAME/MODIFY/CHANGE COLUMN 等）或有迁移被移除 | 视为不安全 | 同上 | 同上 |
| 缺少 SCHEMA 清单（老镜像等） | `UNCONFIRMED` | 同上 | 同上 |

升级前 `upgrade.sh` 会把这个判定打印出来；非 `NONE`/`DECLARED_COMPATIBLE` 时必须显式 `--accept-schema-risk` 才会继续——这表示你**知道**失败后没有自动回滚。

## 2. 场景与操作

### 2.1 升级后健康检查失败（schema 安全）
`upgrade.sh` 已自动回滚到旧镜像并复检。日志末尾会写 `ROLLED BACK to <旧版本>`，`state.env` 的 `LAST_OP_RESULT=FAILED_ROLLED_BACK`。
失败版本的容器已被旧版本容器替换，所以 `docker logs` 看不到它；排查看 `${COMPANY_BI_HOME}/app/logs/`（文件日志）以及升级脚本输出里打印的最后 30 行。新版本镜像仍在本机，可用于复现。

### 2.2 升级失败，且 schema 兼容性未证明
脚本停止新应用（MySQL 保持运行、未改动），`state.env` 写 `LAST_OP_RESULT=FAILED_MANUAL_ROLLBACK_REQUIRED`、`PENDING_MANUAL=yes`，阻止继续升级。二选一：

**a) 向前修复**：读 `app/logs/` 与 `docker logs companybi-app`，发新版本，再次 `upgrade.sh`（先 `rollback.sh --clear-pending` 确认你已核对过状态）。

**b) 回到旧版本（会丢失备份之后写入的数据）**：
```bash
scripts/status.sh                                     # 看 LAST_BACKUP
scripts/rollback.sh --to <旧版本> --restore-db <LAST_BACKUP 文件>
```
脚本会：停应用 → 先对**当前**库做一份 `pre-rollback-*` 安全备份 → `DROP DATABASE` 再用备份重建 → 启动旧镜像 → 健康检查。输入 `yes` 才会执行。丢失的数据仍可从安全备份里人工找回。

### 2.3 升级成功但之后发现问题
- 若 `ROLLBACK_MODE=auto`（schema 无变化/已声明兼容）：`scripts/rollback.sh`（默认回到 `PREVIOUS_GOOD_VERSION`）。
- 否则脚本会拒绝并说明原因；选择向前修复，或 `--restore-db`（同 2.2b，会丢掉升级后的新数据）。

### 2.4 回滚本身失败
适用于两种情况：`rollback.sh` 手动回滚，以及 2.1 的 `upgrade.sh` **自动**回滚（旧版本回滚后仍不健康，`LAST_OP_RESULT=FAILED_ROLLBACK_ALSO_FAILED`）。
脚本只尝试一次：目标版本不健康 → 停应用，`PENDING_MANUAL=yes`，打印 `docker logs` / 备份位置。**不会**再切回新版本（避免来回切换）；MySQL 不动。人工排查后，用 `rollback.sh --clear-pending` 确认；若怀疑数据已损坏，走 2.2b / 2.6 的 `--restore-db`。

### 2.5 脚本在切换中途被中断（断网、Ctrl-C、进程被杀）
`EXIT` 钩子会标 `PENDING_MANUAL=yes`、`LAST_OP_RESULT=INTERRUPTED`，并提示备份位置。先 `scripts/status.sh` 看实际运行的版本与容器状态（它会标出"记录版本 ≠ 运行版本"的漂移），再决定走 2.2a 还是 2.2b。

### 2.6 数据库损坏 / 误删
```bash
scripts/rollback.sh --restore-db <备份文件>       # 不写 --to：数据恢复到备份，版本保持不变
```
脚本会校验备份文件（`gzip -t` 与库名），恢复前先对当前库做安全备份，需输入 `yes` 确认。需要同时换版本时再加 `--to <版本>`（受第 1 节规则约束）。

### 2.7 完全手动恢复（脚本不可用时）
```bash
cd /opt/companybi
docker compose --project-name companybi --env-file .env --env-file ${COMPANY_BI_HOME}/releases/version.env stop app    # 只停应用，不要 down
# 先给"当前"库留一份安全转储（和 rollback.sh 一样：恢复会丢掉备份之后的数据，这份是唯一的找回途径）
( umask 077; docker exec companybi-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -uroot --single-transaction --databases companybi_meta' | gzip > ${COMPANY_BI_HOME}/backups/pre-manual-restore.sql.gz )
# 必须先删库：备份是 `mysqldump --databases`（无 add-drop-database），直接导入只会覆盖备份里有的表，
# 备份之后新版本多建出来的表/数据会残留，和"恢复到备份时刻"不一致。库名默认 companybi_meta（.env 的 COMPANY_BI_DB_NAME）。
docker exec companybi-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -e "DROP DATABASE IF EXISTS companybi_meta"'
gunzip -c <备份文件> | docker exec -i companybi-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot'
# 把 releases/version.env 改为想运行的版本，然后：
docker compose --project-name companybi --env-file .env --env-file ${COMPANY_BI_HOME}/releases/version.env up -d --no-deps app
```

## 3. 如何声明"新版本兼容旧 schema"

只在**人工验证**过之后：
1. 从旧版本的 manifest / `status.sh` / 镜像内 `SCHEMA` 取得旧版 `schema.fingerprint`（64 位十六进制）。
2. 对照新版本相对旧版的迁移内容（`SCHEMA` 的 `delta`，以及对应 SQL）确认：旧版本代码能在新结构上正常启动与运行（新增表/可空列通常可以；删列、改类型、改名、数据改写则不行）。
3. 在新版本的 `deploy/companybi/schema-compat.txt` 加一行 `<旧指纹>  # 说明`，提交，**重新构建**新版本。声明跟随镜像，`upgrade.sh` 读的是镜像内的声明。
4. 不要为了让脚本放行而随手加声明；声明代表发布负责人的判断。

## 4. 备份保留策略

- 升级前备份（`pre-upgrade-*`）、回滚前安全备份（`pre-rollback-*`）永不被脚本清理，磁盘紧张时人工处理。
- 定时备份用 `backup-db.sh --label scheduled --keep N`，只清理自己标签的文件。
- 备份在同一块盘上不防磁盘损坏，请复制到机外。
