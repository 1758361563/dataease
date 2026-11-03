# Company BI 日常简化更新：给本地 AI 的执行手册

适用约定：用户已完成本地功能验收，要求 AI 整理 Commit、Push 并更新现有测试 ECS。沿用 `deploy/companybi`，执行升级脚本内置检查，完成后交付结果。

本手册不是首次安装教程。更新时间：2026-11-02。

## 1. 直接复制给 AI 的任务指令

```text
按 deploy/companybi/docs/simple-update-runbook.md 执行一次日常简化更新。

本地功能由我验收。请连续完成：
核对本轮文件 → 明确选择文件 Commit → Fetch/Push并确认远程SHA →
基于干净的已推送SHA构建 → 导出 → SFTP断点续传 → SHA256校验 →
调用现有upgrade.sh → 根据退出码及内置健康检查判定结果 → 保存发布记录和备份。

仓库：D:\Coding_project\scent_library\test\dataease
目标分支：company/bi
目标服务器：SSH别名companybi-temp，仅操作新测试ECS。
当前服务器身份与连接资料：D:\companybi-private\servers.md，以实际记录为准。
服务器部署目录：/opt/companybi；持久化目录：/data/companybi。

授权对本轮有效修改Commit、Push，构建并部署至上述服务器。
不重复功能验收，不运行单元测试、类型检查、完整接口检查、浏览器验收、
static-audit.sh、local-e2e.sh、release-verify.sh或数据库恢复演练。
保留build.sh和upgrade.sh本身执行的编译、产物校验、备份及健康检查。

不要修改产品代码、依赖或部署架构来“顺手优化”。
遇到阻断构建的问题，报告具体错误和最小修复范围；用户已授权修复时再修复并重建。
上传中断直接续传；升级已启动时继续观察同一次任务，不重复启动升级。
脚本判定成功后结束，不额外追加一轮验收。

不要触碰其他服务器、原观远、业务RDS或生产ETL，不发送飞书测试消息。
保留真实.env、已有元数据库、业务配置和旧镜像。沿用现有密钥与SSH配置。
真实凭证、私有服务器资料、SQL备份和镜像包不进Git、不输出到对话。

不需要逐步询问确认。范围不明、远程分叉、Schema风险未确认、升级失败且
无法自动回滚、必须改生产资源或产生付费操作时，才停下说明具体问题。

最终只汇报：Commit/Push、部署版本、脚本结果、备份/回滚位置、耗时及阻塞项。
明确说明本轮采用用户本地验收，AI只执行发布和脚本内置检查。
```

用户若指定 SHA，优先部署该 SHA；若指定文件或功能范围，按其范围提交。用户说“两项修改”可能涉及多个文件，不按文件数量猜测任务内容。

## 2. 检查范围：只做发布所需的检查

必须做：

- 核对分支、远程、Diff摘要、暂存清单和明显敏感文件，确定提交内容。
- 确认镜像来自目标提交且 `git_dirty=clean`，平台为 `linux/amd64`。
- 校验交付文件，调用现有升级脚本，读取它的最终结果。
- 保存发布记录和升级前备份；已有机外备份方案继续沿用。

不另行做：代码审计、功能回归、测试账号创建、实际改密、测试仪表板创建、业务数据库查询、141个静态资源遍历、浏览器点击验收和本地隔离栈演练。

`build.sh` 内部可能执行前端 lint、编译及其他构建步骤。照常运行，不为缩短时间修改脚本或绕过构建错误。`upgrade.sh` 会检查首页、一个元数据库相关接口、容器健康、版本、管理员记录、Flyway及启动错误；这些属于一次升级的内置步骤，不是额外验收。

## 3. 执行前：取得本次变量

以下本地命令在 Windows PowerShell 中运行。可按当前机器调整 Git Bash/WSL 路径。

```powershell
$repo = 'D:\Coding_project\scent_library\test\dataease'
$sshAlias = 'companybi-temp'
$runDir = Join-Path 'D:\Coding_project\scent_library\test\.companybi-prep' ('release-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $runDir -ErrorAction Stop | Out-Null
Set-Location -LiteralPath $repo
git branch --show-current
git remote -v
git status --short
git diff --stat
git diff --cached --stat
```

日志放在仓库外的 `$runDir`，避免日志本身让发布工作区变脏。

读取私有 `servers.md` 确认新 ECS 的实例身份、SSH别名和部署目录。通过 SSH 读取 `hostname`、应用版本标签；与私有资产记录对应后继续。当前版本必须实时读取，不把历史发布版本抄成当前版本。

```powershell
ssh -o BatchMode=yes -o ConnectTimeout=15 $sshAlias hostname
ssh -o BatchMode=yes -o ConnectTimeout=15 $sshAlias 'sudo -n docker inspect -f ''{{index .Config.Labels "com.companybi.version"}}'' companybi-app'
```

SSH不可用时先处理连接问题。宿主机、私网IP、安全组或域名有变化，更新私有资产记录。密钥文件由SSH自动使用，不读取或复制私钥内容。

## 4. Commit与Push

1. `git fetch origin company/bi`，确认当前分支为 `company/bi`，检查与远程的差异。
2. 查看本轮Diff和新增文件，明确筛选待发布文件。新Logo、SVG、许可证、被引用组件和样式都应包含。检查是否有被 `.gitignore` 忽略的必要源码；不自动提交所有被忽略文件。
3. 用 `git add -- <明确路径...>` 暂存；再次查看 `git diff --cached --stat`、`git diff --cached --name-only`。不使用不经筛选的 `git add .`。
4. 排除真实 `.env`、密钥、凭证、业务原始数据、SQL、镜像tar、构建缓存及私有资产说明。按实际功能边界提交，普通一轮UI改动通常一个提交即可。
5. `git push origin company/bi`，检查退出码。用 `git ls-remote origin refs/heads/company/bi` 确认远程SHA等于要发布的SHA。

远程已包含目标提交时，不制造重复提交。远程分叉时先分析差异，不强推、不自动合并无关分支。

```powershell
git fetch origin company/bi
if ($LASTEXITCODE -ne 0) { throw 'Fetch failed' }
git rev-list --left-right --count HEAD...origin/company/bi
# 明确筛选文件、提交，然后：
git push origin company/bi
if ($LASTEXITCODE -ne 0) { throw 'Push failed' }
$sha = (git rev-parse HEAD).Trim()
$remoteLine = git ls-remote origin refs/heads/company/bi
if ($LASTEXITCODE -ne 0 -or (($remoteLine -split '\s+')[0] -ne $sha)) {
    throw 'Remote branch SHA differs from release SHA'
}
```

构建目录必须干净。有与本轮无关的开发或未跟踪文档时，不覆盖、不stash、不为清空状态而提交它们。可使用干净的独立发布检出，例如在仓库外创建目标SHA的临时worktree，然后在该目录执行后续构建；记录其路径，结束后只清理本轮创建且确认干净的检出。

```powershell
# 仅当需要隔离无关本地改动时使用；$sha必须是已推送的目标提交。
$releaseCheckout = Join-Path $runDir 'source'
git worktree add --detach $releaseCheckout $sha
if ($LASTEXITCODE -ne 0) { throw 'Release checkout failed' }
Set-Location -LiteralPath $releaseCheckout
$repo = $releaseCheckout
```

## 5. 构建与导出：一次最终产物

在干净检出内执行现有脚本，默认利用缓存。没有需要就不加 `--no-cache`，正式发布不使用 `--allow-dirty`。

```powershell
& 'C:\Program Files\Git\bin\bash.exe' deploy/companybi/scripts/build.sh `
  --maven-central-mirror https://maven.aliyun.com/repository/public `
  *> (Join-Path $runDir 'build.log')
if ($LASTEXITCODE -ne 0) { throw 'Build failed: inspect build.log' }

# 版本由脚本生成，读取输出，不手写日期或沿用旧标签。
$versionLines = @(Select-String -LiteralPath (Join-Path $runDir 'build.log') -Pattern '^build: version\s+=\s+(\S+)')
if ($versionLines.Count -ne 1) { throw 'Cannot determine release version' }
$version = $versionLines[0].Matches[0].Groups[1].Value
$dist = Join-Path $repo "deploy/companybi/dist/$version"
$manifest = Join-Path $dist "companybi_$version.manifest"
if (-not (Select-String -LiteralPath $manifest -SimpleMatch "git_sha=$sha")) {
    throw 'Build manifest SHA differs from target'
}
```

日志出现构建警告不等于失败；以退出码、`build: OK`及产物元数据为准。构建中的失败才定位对应错误，不顺带清理历史警告。

导出使用现有 `export-image.sh`。本机过去使用WSL成功；若当前工作目录是Windows路径，可这样运行：

```powershell
wsl.exe --cd $repo --exec bash deploy/companybi/scripts/export-image.sh --version $version `
  *> (Join-Path $runDir 'export.log')
if ($LASTEXITCODE -ne 0) { throw 'Image export failed' }
```

日常应用更新不加 `--with-mysql`，不重复上传未变化的MySQL镜像。已有与目标SHA一致、clean、amd64且交付文件齐全的镜像时，可直接复用；核对manifest与文件SHA后跳过重建。

本地Docker镜像ID与ECS导入后的ID可能因OCI/index与config表示不同而不同，不只凭这一点重建。依据tar的SHA256、镜像内完整Git SHA、版本、平台及clean标记对应发布资产。

## 6. 上传：默认支持断点续传

上传到 `/home/ecs-user/companybi-releases/<version>/`，保留旧版本。上传应用tar、tar校验文件、部署bundle及校验文件、manifest和release记录。

```powershell
if ($version -notmatch '^\d+\.\d+\.\d+-\d{8}-[0-9a-f]{8}$') { throw 'Unexpected version format' }
$incoming = "/home/ecs-user/companybi-releases/$version"
$tarName = "companybi_${version}_linux-amd64.tar"
ssh -o BatchMode=yes -o ConnectTimeout=15 $sshAlias "mkdir -p $incoming && touch $incoming/$tarName"
if ($LASTEXITCODE -ne 0) { throw 'Cannot prepare incoming directory' }
# touch只确保文件存在，不清空已有文件；重试时保留远端已有内容。

$localDist = $dist.Replace('\', '/')
$batch = @"
lcd $localDist
cd $incoming
put -a $tarName
put $tarName.sha256
put companybi_deploy_${version}.tar.gz
put companybi_deploy_${version}.tar.gz.sha256
put companybi_${version}.manifest
put release_${version}.txt
"@
$batchPath = Join-Path $runDir 'upload.sftp'
[IO.File]::WriteAllText($batchPath, $batch.Replace("`r", ''), [Text.UTF8Encoding]::new($false))
$uploaded = $false
for ($attempt = 1; $attempt -le 4; $attempt++) {
    "$(Get-Date -Format o) SFTP attempt $attempt" | Out-File (Join-Path $runDir 'upload.log') -Append
    sftp.exe -B 65536 -R 64 -o BatchMode=yes -o ConnectTimeout=15 -o ServerAliveInterval=15 `
      -b $batchPath $sshAlias >> (Join-Path $runDir 'upload.log') 2>&1
    if ($LASTEXITCODE -eq 0) { $uploaded = $true; break }
}
if (-not $uploaded) { throw 'Upload interrupted; retain partial tar for next resume' }

ssh -o BatchMode=yes -o ConnectTimeout=15 $sshAlias `
  "cd $incoming && sha256sum -c $tarName.sha256 && sha256sum -c companybi_deploy_${version}.tar.gz.sha256"
if ($LASTEXITCODE -ne 0) { throw 'Artifact checksum failed; do not upgrade' }
```

只重试上传，不自动修改Clash、SSH配置或服务器安全组。校验不一致时保留日志并重新交付完整正确文件；不继续加载不完整的镜像。路径含空格时按SFTP语法给 `lcd` 路径加引号；上述固定工作区路径无空格。

## 7. 升级：只运行一次现有脚本

目标命令：

```bash
sudo -n /opt/companybi/scripts/upgrade.sh \
  --version <version> \
  --image-tar /home/ecs-user/companybi-releases/<version>/companybi_<version>_linux-amd64.tar \
  --yes
```

日常发布不先跑一遍 `--dry-run` 再重复相同预检；升级脚本本身会完成预检。用户要求预览或涉及新部署条件时才单独dry-run。

脚本依次负责预检、加载镜像及Schema评估、元数据库/env/conf备份、只重建应用、健康检查、状态记录或安全条件下的自动回滚。不要手工执行Compose替代它，不停止MySQL、不重建元数据库、不覆盖真实 `.env`。

### SSH中断不影响升级的启动方式

建议通过root后台包装脚本保存完整日志和退出码。先再次确认当前实例身份及原版本与本轮开始时一致；如果版本已变化，判断是否有其他发布正在运行。

下面PowerShell生成的shell内容应去掉CR，版本占位符只替换成前面校验过的 `$version`：

```powershell
$remoteScript = @'
set -eu
version='__VERSION__'
state="/var/lib/companybi-updates/$version"
logfile="/var/log/companybi-upgrade-$version.log"
umask 077
# 目录已存在表示曾启动过：先读exit/log和发布状态，不再自动启动第二次。
if [ -e "$state" ]; then echo 'Existing upgrade task: inspect it first'; exit 1; fi
mkdir -p "$state"
cat > "$state/run.sh" <<'RUN'
#!/bin/bash
version='__VERSION__'
/opt/companybi/scripts/upgrade.sh --version "$version" \
  --image-tar "/home/ecs-user/companybi-releases/$version/companybi_${version}_linux-amd64.tar" \
  --yes > "/var/log/companybi-upgrade-$version.log" 2>&1
rc=$?
printf '%s\n' "$rc" > "/var/lib/companybi-updates/$version/exit"
exit "$rc"
RUN
chmod 700 "$state/run.sh"
nohup bash "$state/run.sh" </dev/null >/dev/null 2>&1 &
printf 'upgrade pid=%s log=%s\n' "$!" "$logfile"
'@
$remoteScript = $remoteScript.Replace('__VERSION__', $version)
$remoteScript | ssh -o BatchMode=yes -o ConnectTimeout=15 $sshAlias 'tr -d "\r" | sudo -n bash -s'
if ($LASTEXITCODE -ne 0) { throw 'Upgrade launch failed or existing task found' }
```

包装脚本内部不要在 `upgrade.sh` 前加 `set -e`，否则失败时可能没有机会写入退出码。上面外层的 `set -eu` 只用于创建后台任务。

观察同一任务的日志尾部和退出码即可：

```powershell
ssh -o BatchMode=yes -o ConnectTimeout=15 $sshAlias "sudo -n tail -n 20 /var/log/companybi-upgrade-$version.log"
ssh -o BatchMode=yes -o ConnectTimeout=15 $sshAlias "sudo -n cat /var/lib/companybi-updates/$version/exit"
```

退出码文件暂不存在表示尚未结束或启动异常，结合日志及进程判断；不凭SSH断线认定升级失败，不重新发起升级。

### 结果判定

- 成功：退出码0，日志中 `UPGRADE OK`、`HEALTH: PASS`，运行版本与目标一致。收尾后结束。
- 未发生切换的预检失败：处理该项发布阻塞，不扩大为功能审查。
- Schema兼容性未确认：脚本拒绝时报告具体分类和差异，等待明确决策；不自行添加 `--accept-schema-risk` 或伪造兼容声明。
- 启动失败且脚本自动回滚：报告“本轮升级失败、已回滚至旧版本”，保存日志和备份，不继续循环升级。
- `PENDING_MANUAL=yes` 或回滚失败：保存现场，依据 [rollback.md](rollback.md) 报告恢复步骤，等待操作授权。不要自动恢复数据库或删除数据。

本地部署脚本与ECS已安装脚本有变化时，先判断本次是否必须更新运行脚本/Compose/配置。普通应用更新不解压bundle覆盖 `/opt/companybi`。确实需要更改部署机制时，单独说明最小变更，按相应文档处理，不能把它伪装为普通镜像更新。

## 8. 收尾与交付

1. 从本次日志读取升级前SQL备份路径及env/conf备份，保存到 `D:\companybi-private\<本次发布目录>`，核对传输SHA256。SQL、真实env和私有配置不经过项目工作区；二进制备份通过SFTP、SCP或SSH二进制流传输，不用PowerShell文本管道写SQL压缩包。
2. 保存本次build/export/upload/upgrade日志和简短发布记录。记录完整SHA、版本、tar大小/SHA256、脚本退出码、旧版本、备份位置及耗时。记录版本信息足够，不打印真实env内容。
3. 更新私有 `servers.md` 的当前版本和发布记录位置。仓库内文档只写通用流程，服务器私有标识和连接资产继续放Git外。
4. 汇报剩余未提交文件及原因。只清理本轮创建的临时检出/临时对象；保留旧镜像、原有本地开发环境和他人工作。

发布后只补记录，不为更新记录而再次构建和部署。若记录产生了新Git提交，应分别标明“代码HEAD”和“已部署SHA”；新提交不意味着服务器自动更新。

最终回复模板：

```text
已完成 Commit → Push → 构建 → 上传 → 部署。
- 提交：<sha>，分支company/bi，Push结果及工作区剩余内容。
- 运行版本：<version>。
- 升级脚本：exit=<code>，HEALTH=<结果>；旧版本/回滚状态。
- 备份与发布记录：<私有路径>。
- 耗时：构建/上传/应用切换/总耗时（按日志实际时间）。
本轮采用用户本地验收，只执行发布及脚本内置检查。
```

## 9. 什么时候切换到完整发布流程

首次安装、迁移部署环境、修改安装/升级/备份/回滚机制，或用户明确要求完整验证时，使用 [release-process.md](release-process.md) 与 [deployment.md](deployment.md) 的对应流程。

Schema或运行依赖变化时先评估脚本输出和既有兼容证据；缺少证据才提出具体补充验证，不自动把所有日常发布扩大成全套测试。认证、业务功能已由用户本地验收时，默认沿用本手册，遇到实际发布失败再处理。
