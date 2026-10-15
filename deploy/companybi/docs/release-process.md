# 发布流程（直接基于 `company/bi`）

目标：不再维护长期独立的部署分支。部署工具（本目录）和产品代码在同一条主线 `company/bi` 上演进，**发布 = 在干净的 `company/bi` 提交上构建**。

## 1. 为什么曾经需要部署分支（以及现在为什么不需要）

整合前，`company/bi` 从干净 checkout **构建不出来**，原因是三个产品侧缺口，已在整合分支里修复：

| 缺口 | 症状 | 修复 | 行为影响 |
|---|---|---|---|
| `SingleCategoryRank.java` 被 `.gitignore` 的 `src/main/**` 白名单机制静默忽略，从未入库；`DefaultChartHandler` 却引用它 | 干净 checkout 编译失败 | 入库（与开发目录里一直在用的那份**逐字节相同**） | 无：同一份源码 |
| `core-frontend/package-lock.json`（及 `flushbonading/`）未入库，且与 `package.json` 不同步 | `npm ci` 失败，构建不可复现 | 入库；唯一与开发目录旧 lock 的差别是 `yaml`：作为 vite 的**可选 peer**提升为 2.9.1，`yaml-eslint-parser` 继续用嵌套的 1.10.3 | 无产物差异：只影响构建工具链的依赖解析 |
| `CompanyFeishuJdbcStore.ensureIdentityStatusColumn()` 在 `@PostConstruct` 里对尚不存在的表做 `ALTER` | **空库**首次启动崩溃（建表由之后的 `ApplicationRunner` 完成） | 表不存在时直接返回（建表脚本本身已含该列）；旧表仍走原来的 `ALTER` | 已有库：路径不变。空库：从"崩溃"变为"正常启动" |

没有改动任何业务计算、权限、图表或仪表板代码。

## 2. 发布步骤（在 `company/bi` 上）

```bash
# 0. 工作区必须干净；所有要发布的东西都已 commit
git status --short                                   # 必须为空
bash deploy/companybi/tests/static-audit.sh          # 必须 PASS

# 1. 构建（拒绝脏工作区；版本 = <dataease版本>-<提交日期UTC>-<sha8>）
bash deploy/companybi/scripts/build.sh
#    注意看开头的 WARNING：若列出了"被 git 忽略、不会进发布"的文件，而运行时需要它们 → 先入库再重建

# 2. 导出传输文件
bash deploy/companybi/scripts/export-image.sh --version <version> --with-mysql

# 3. 用【正式发布文件本身】验证（隔离栈，不碰任何真实数据）
bash deploy/companybi/tests/release-verify.sh --dist deploy/companybi/dist/<version> --version <version> --expect-sha "$(git rev-parse HEAD)"
#    S 阶段：导入/初始化/登录/公司接口/仪表板/重启持久化/版本识别/RELEASE↔提交
#    R 阶段：造用户+权限+仪表板 → 备份 → 破坏 → 恢复 → 核对（含 rollback.md 2.7 无脚本路径）

# 4. 记录：release_<version>.txt（含提交、镜像 ID、tar SHA256）；建议给该提交打 tag（本地操作，是否推送由你决定）
git tag -a "release/<version>" -m "Company BI <version>"
```

要点：
- **镜像永远对应一个提交**。验证过的是哪个提交，部署的就是哪个；之后再改任何文件（哪怕只是文档）都会产生新提交，**需要重新构建并重新验证**，不能把旧镜像的结果当作新镜像的结果。
- `export-image.sh`、`release-verify.sh` 都只按显式名称操作自己的对象（`companybi:<v>`、`cbirel-*`）。

## 3. 改动了数据库结构时（Flyway / `db/company/*.sql`）

1. 新版本的 `schema.fingerprint` 会变化，`upgrade.sh` 会把分类打印出来（`NONE` / `DECLARED_COMPATIBLE` / `UNCONFIRMED`，并标出破坏性关键字）。
2. 只有**人工验证过**"旧版本能在新结构上运行"之后，才往 `schema-compat.txt` 加旧指纹并**重新构建**（见 `rollback.md` §3）。否则回滚只能走 `--restore-db`。
3. 破坏性变更（DROP/TRUNCATE/DELETE/RENAME/MODIFY…）永远不会被自动回滚放行。
4. 本机 e2e 里的 schema 差异是**模拟**的；第一个真实带迁移的版本出现时，需要在隔离栈上用真实的旧→新版本对做一次升级 + 失败回滚演练。

## 4. 保持"可发布"的日常纪律
| 纪律 | 原因 |
|---|---|
| 新增 `core-backend/src/main` 下的文件后，用 `git status` 确认它出现在待提交里 | 这个目录的 `.gitignore` 是逐路径白名单，新文件会被静默忽略；`build.sh` 开头的警告是兜底，不是替代 |
| 前端依赖变更后提交 `package-lock.json` | 否则 `npm ci` 与 `package.json` 不同步，构建不可复现 |
| 不把真实数据库地址/密码写进被跟踪的文件 | 旧的硬编码数据源仍在 Git 历史里（见交付报告，需轮换）；镜像构建时会把 jar 里的硬编码数据源替换成占位符，但这不能替代轮换 |
| 发布提交上不带 `-dirty` | `install.sh`/`upgrade.sh` 会拒绝 dirty 镜像 |
