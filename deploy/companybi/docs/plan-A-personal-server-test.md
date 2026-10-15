# 方案 A：个人阿里云服务器隔离部署测试（拟定，尚未执行）

> 目标：在一台**与公司无关**的个人服务器上，用**正式发布文件**走完"传输 → 导入 → 首次安装 → 持久化 → 升级 → 失败回滚 → 数据库恢复 → 资源观察"，
> 把本机（WSL）测不到的东西测掉：**真实的 Linux 主机、真实的网络传输、旧版本 Docker 的 `docker load` 兼容性、长时间资源曲线**。
> 这台机器上**不允许出现**任何公司真实数据、真实数据库连接、真实飞书凭据。飞书保持 `DRY_RUN=true`。

## 0. 前置条件与建议

| 项 | 建议 | 说明 |
|---|---|---|
| 规格 | ≥ 2 vCPU / 4 GB 内存 / 40 GB 系统盘 | 尽量**贴近塔内 ECS 的可用余量**，而不是越大越好：测的是"紧"的情况。具体数值等方案 B 的报告出来再对齐 |
| 系统 | 与塔内同族（Alibaba Cloud Linux / CentOS / Ubuntu） | 以方案 B 的 `/etc/os-release` 为准 |
| Docker 版本 | **与塔内一致**（方案 B 给出后再装；没有就先用最新版跑完整流程） | `docker load` 兼容性只能这样验证 |
| 安全组 | 只放行 22（限定你的 IP）；**不要放行 30005** | 应用只绑 `127.0.0.1`，用 SSH 隧道访问 |
| 数据 | 只用测试数据 | 不接公司库，不放飞书凭据 |

## 1. 本机准备发布文件（Git Bash / WSL，不在服务器上构建）

前提：已按"主线整合"把发布提交放到 `company/bi`（见 `docs/release-process.md` 或本轮报告），工作区干净。

```bash
bash deploy/companybi/scripts/build.sh                       # 拒绝脏工作区；产出 dist/<version>/
bash deploy/companybi/scripts/export-image.sh --version <version> --with-mysql
# 本机先验证这批文件本身（不依赖服务器）：
bash deploy/companybi/tests/release-verify.sh --dist deploy/companybi/dist/<version> --version <version> --expect-sha "$(git rev-parse HEAD)"
```

需要 3 个文件 + 记录：`companybi_<v>_linux-amd64.tar(.sha256)`、`companybi_deploy_<v>.tar.gz(.sha256)`、`mysql_8.4.5_linux-amd64.tar(.sha256)`、`release_<v>.txt`。
**升级演练还需要一个"旧版本"**：用上一个已导出的版本（例如上一轮的 `2.10.25-20261015-a5b7746e`）。

## 2. 传输与校验

```bash
scp dist/<v>/companybi_*_linux-amd64.tar* dist/<v>/companybi_deploy_*.tar.gz* dist/<v>/mysql_8.4.5_linux-amd64.tar* dist/<v>/release_*.txt \
    <user>@<server>:/root/companybi-incoming/        # 目录自选；旧版本的文件放另一个子目录

ssh <user>@<server> 'cd /root/companybi-incoming && sha256sum -c *.sha256'     # 必须全部 OK
```

## 3. 装 Docker（只在个人服务器上可以这么做；**对塔内永远不要**）

先跑方案 B 的只读脚本，**验证它在一台真实远程主机上也能用**（同样用 stdin 方式）：

```bash
ssh <user>@<server> 'bash -s' < deploy/companybi/scripts/tower-readonly-check.sh | tee personal-server-report.txt
```

按塔内版本固定安装（Ubuntu 示例，Alibaba Cloud Linux/CentOS 用 `dnf config-manager --add-repo https://mirrors.aliyun.com/docker-ce/linux/centos/docker-ce.repo` 与 `docker-ce-<ver>`）：

```bash
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://mirrors.aliyun.com/docker-ce/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
echo "deb [arch=amd64 signed-by=/etc/apt/keyrings/docker.gpg] https://mirrors.aliyun.com/docker-ce/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" | sudo tee /etc/apt/sources.list.d/docker.list
sudo apt-get update && apt-cache madison docker-ce | head           # 选出与塔内一致的版本串
sudo apt-get install -y docker-ce=<VERSION_STRING> docker-ce-cli=<VERSION_STRING> containerd.io docker-compose-plugin
sudo usermod -aG docker "$USER"                                     # 重新登录后生效
docker version; docker compose version
```

**门槛 3.1（必须过）**：`docker load -i companybi_<v>_linux-amd64.tar` 在该 Docker 版本上成功，且 `docker image inspect companybi:<v> -f '{{.Id}}'` 等于 `release_<v>.txt` 里的 `image_id`。
失败 → 立即停，记录 Docker 版本与报错：这就是塔内同样会遇到的问题（对策：用经典镜像库的引擎重新导出 tar，见"失败处理"）。

## 4. 首次安装（与塔内相同的脚本、相同的步骤）

```bash
cd /root/companybi-incoming && tar xzf companybi_deploy_<v>.tar.gz && cd companybi
bash scripts/install.sh --init-env              # 生成 .env（随机库密码/令牌密钥）；BOOTSTRAP 密码故意留空
vi .env                                          # 设 COMPANY_BI_BOOTSTRAP_PASSWORD（自选强密码）；确认 COMPANY_BI_HOME（例如 /data/companybi）
chmod 600 .env
bash scripts/install.sh --version <v> --image-tar ../companybi_<v>_linux-amd64.tar --mysql-image-tar ../mysql_8.4.5_linux-amd64.tar
bash scripts/health-check.sh --expect-version <v>
bash scripts/status.sh
```

**验收（每条都要有输出留底）**

| # | 检查 | 命令/做法 | 通过标准 |
|---|---|---|---|
| A1 | 安装退出码、耗时 | `install.sh` 输出 | exit 0；记录耗时（本机 77 s，作参考） |
| A2 | 镜像 ID 与记录一致 | `docker image inspect companybi:<v> -f '{{.Id}}'` | 等于 `release_<v>.txt` 的 `image_id` |
| A3 | 版本识别 | `status.sh` / `health-check.sh --expect-version <v>` | 显示 `<v>`；传错版本必须失败 |
| A4 | RELEASE 与提交一致 | `docker exec companybi-app cat /opt/apps/release/RELEASE` | `git_sha` = 发布提交，`git_dirty=clean` |
| A5 | 只监听回环 | `ss -ltn \| grep 30005` | 只有 `127.0.0.1:30005`；**从公网访问必须不通** |
| A6 | 隧道登录 | 本地 `ssh -L 30005:127.0.0.1:30005 <user>@<server>`，浏览器开 `http://localhost:30005` | admin + 你设的 BOOTSTRAP 密码能登录 |
| A7 | 公司二开接口 | 登录后看用户管理/权限页；或用 `curl` 带 token 调 `/de2api/company/bi/me` | 返回 admin、角色 ADMIN |
| A8 | 创建仪表板 | 在界面里新建仪表板并保存 | 刷新后仍在 |
| A9 | MySQL 参数生效 | `docker exec companybi-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -N -uroot -e "select @@innodb_buffer_pool_size,@@transaction_isolation"'` | `268435456`、`READ-COMMITTED` |
| A10 | 飞书不外发 | `docker exec companybi-app printenv COMPANY_FEISHU_DRY_RUN` | `true` |
| A11 | 没有旧生产数据源残留 | `docker exec companybi-app sh -c 'env' \| grep -iE 'jdbc\|mysql\|db_host' \| sed -E 's/((PASSWORD\|SECRET)[A-Z_]*=).*/\1***/'`（口令已打码，不要去掉 sed） | 数据库主机只出现 `companybi-mysql`，没有任何其它主机/IP |

## 5. 持久化演练

```bash
docker restart companybi-app companybi-mysql            # 只写显式名称
bash scripts/health-check.sh --wait 300
# 再模拟"容器被整个删除后重建"（只删我们自己的两个容器，数据在 bind mount 里）：
docker rm -f companybi-app companybi-mysql
. scripts/lib.sh && COMPANY_BI_ENV_FILE="$PWD/.env" load_env && compose up -d      # 或重新执行 upgrade/install 对应流程
bash scripts/health-check.sh --wait 300
```
**通过标准**：A8 创建的仪表板仍在，admin 令牌（重启前登录得到的）仍有效，`COMPANY_BI_HOME/mysql/data` 没变空。

## 6. 升级与失败回滚演练

**6.1 正常升级**：先装旧版本（A 部分用旧版本的文件装），再升到新版本。
```bash
bash scripts/upgrade.sh --version <新版本> --image-tar ../companybi_<新版本>_linux-amd64.tar --dry-run   # 只看评估
bash scripts/upgrade.sh --version <新版本> --image-tar ../companybi_<新版本>_linux-amd64.tar
bash scripts/health-check.sh --expect-version <新版本>
```
通过标准：退出 0；`status.sh` 显示 current=新、previous=旧、`rollback_mode=auto`（两版 schema 指纹相同时）；仪表板/用户仍在；`backups/` 里有 `pre-upgrade-*`（权限 600）。

**6.2 失败升级自动回滚（演练镜像，只用于测试）**：在**本机**用已导出的新镜像派生一个"启动即失败"的镜像并导出，传到服务器：
```bash
# 本机
cat > /tmp/Dockerfile.broken <<'EOF'
FROM companybi:<新版本>
LABEL com.companybi.version="<新版本>-drill-broken"
RUN sed -i 's/^version=.*/version=<新版本>-drill-broken/' /opt/apps/release/RELEASE
ENTRYPOINT ["/bin/sh","-c","echo DRILL_BOOT_FAILURE; sleep 2; exit 1"]
EOF
docker build -t companybi:<新版本>-drill-broken -f /tmp/Dockerfile.broken /tmp && docker save companybi:<新版本>-drill-broken -o companybi_<新版本>-drill-broken_linux-amd64.tar
```
```bash
# 服务器（当前运行的是 <新版本>）
bash scripts/upgrade.sh --version <新版本>-drill-broken --image-tar ../companybi_<新版本>-drill-broken_linux-amd64.tar --yes
```
通过标准：退出非 0；日志写明 `ROLLED BACK to <新版本>`；应用在跑且健康；`state.env` 里 `LAST_OP_RESULT=FAILED_ROLLED_BACK`、`PENDING_MANUAL=no`；数据完好。**演练镜像不得留在系统里**：`docker rmi companybi:<新版本>-drill-broken`（显式标签）。
> 派生镜像的 schema 指纹与新版本相同，所以走的是"自动回滚"分支。schema 有变化的分支（拒绝、不自动回滚、`PENDING_MANUAL`）本机 e2e 已用**模拟**的 schema 差异验证过；真实迁移版本出现后再补做。

## 7. 数据库备份与真实恢复演练（与本机 `release-verify.sh` 的 R 阶段同一思路）

```bash
# 1) 造数据：在界面里新建用户 rst_user_a（NORMAL）、两个仪表板，并给 rst_user_a 授权其中一个
bash scripts/backup-db.sh --label restore-test               # 记下输出的备份路径（应为 600 权限的 .sql.gz）
# 2) 备份之后做"受控破坏"：再建一个用户 B、撤销 A 的授权、改 A 的角色/密码、删掉一个仪表板
# 3) 恢复
bash scripts/rollback.sh --restore-db <备份文件>              # 输入 yes；脚本会先对"当前库"做 pre-rollback 安全转储
bash scripts/health-check.sh --expect-version <当前版本>
```
通过标准：用户 A 回来（**原密码**可登录）、用户 B 消失、A 的授权与被删仪表板都回来、`pre-rollback-*` 安全转储存在且含备份之后的数据。
再演练一次**无脚本路径**：按 `docs/rollback.md` §2.7 原文逐条执行（含"先删库"），结果必须与脚本路径一致。

## 8. 资源观察（至少 24 小时，最好跨一个"报表高峰"）

```bash
# 每 10 分钟记一行（用 crontab 或手工 tmux 里循环），只读：
while true; do date '+%F %T'; docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}' companybi-app companybi-mysql; free -m | awk 'NR==2{print "host avail(MiB)="$7}'; sleep 600; done | tee -a resource-log.txt
du -sh "$COMPANY_BI_HOME"/{mysql/data,app/logs,backups}       # 日志与备份增长
```
**参考基线（本机实测，方案 A 要用真实服务器复测）**：应用空闲约 0.95 GiB（上限 2 GiB）、MySQL 空闲约 0.2 GiB（上限 768 MiB，`my.cnf` 生效时）。
通过标准：24 h 内两个容器 `RestartCount` 为 0、无 OOM；应用 < 1.4 GiB；MySQL < 0.5 GiB；宿主可用内存始终 > 1 GiB；`app/logs` 增长速度可接受（外推 30 天 < 磁盘剩余的 10%）。

## 9. 收尾（只用显式名称；个人服务器上才可以彻底清除）

```bash
docker rm -f companybi-app companybi-mysql
docker network rm companybi-net
docker rmi companybi:<v> companybi:<旧版本>                    # 显式标签；不要 prune
sudo rm -rf "$COMPANY_BI_HOME"                                  # 先 ls 确认路径是测试目录
```

## 10. 通过标准汇总与停止条件

| 结论 | 条件 |
|---|---|
| **通过，可评审塔内安装** | §3 门槛 3.1 过；§4 全部 A1–A11 过；§5、§6.1、§6.2、§7 全过；§8 达标；全部输出留底 |
| **有条件通过** | 只有"资源观察超标"类问题 → 调整 `.env` 里的内存上限/JVM 参数并复测，**不得**为了过关降低检查标准 |
| **立即停止** | `docker load` 失败；任何一步出现 `PENDING_MANUAL=yes` 而你没有预期；数据丢失；脚本触碰了不属于 `companybi*` 的对象 |

## 11. 这一方案**证明不了**的事（要在塔内评审时单独说明）
- 塔内 Docker/内核/磁盘/并存负载与个人服务器不同；方案 A 通过 ≠ 塔内一定能装。
- 塔内真实内存余量只能由方案 B（只读）+ 观远负责人给出。
- 真实业务数据源（只读账号接入）、真实飞书投递，均不在本方案范围。
- 脚本被 SSH 断线中途杀死的行为：建议在服务器上用 `tmux`/`nohup` 执行升级；断线恢复路径见 `docs/rollback.md` §2.5，目前**未做自动化实测**。

## 12. 失败处理速查
| 现象 | 做法 |
|---|---|
| `docker load` 报格式/EOF 错误 | 记录 Docker 版本；在本机用经典镜像库的引擎（或 `docker buildx build --output type=docker,dest=…`）重新导出 tar，重新计算 SHA256，更新 `release_<v>.txt`；不要手工改 tar |
| 健康检查超时 | `docker logs --tail 100 companybi-app`；`COMPANY_BI_HOME/app/logs/`；`status.sh` |
| 脚本拒绝并说明原因 | 按提示处理，**不要**绕过脚本直接 `docker compose down` 或手动删数据目录 |
