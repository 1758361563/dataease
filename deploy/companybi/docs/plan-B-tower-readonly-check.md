# 方案 B：公司塔内 ECS 只读检查（拟定，尚未执行）

> 状态：**只是方案，没有连接过任何塔内服务器。** 需要你单独授权后才执行。
> 目标：在**不安装、不停止、不重启、不清理、不修改任何东西**的前提下，回答"这台机器能不能放下 Company BI、放在哪、有什么风险"。
> 所有命令已打包进 `scripts/tower-readonly-check.sh`，本机（WSL）已按真实用法（stdin 喂入）试跑通过；塔上的输出内容当然要等授权后才有。

## 0. 授权前需要你确认的四件事

| # | 问题 | 为什么要问 |
|---|---|---|
| 1 | 用哪个账号登录？它**是否在 `docker` 组**？ | 不在组里 `docker ps` 会失败；这时只有"允许 `sudo -n docker …`"才能继续，需要你明确同意用 sudo 做只读 docker 查询（脚本的 `CHECK_SUDO=1`） |
| 2 | 允许的时间窗？ | 脚本含一个 5 秒的 `vmstat` 和一次 `docker stats --no-stream`，对生产影响可忽略，但建议避开观远的报表高峰/批处理窗口 |
| 3 | 观远的负责人是否知情？ | 报告里会出现观远容器的**名称/镜像/状态/端口/内存占用**（不含环境变量与启动参数）。内存趋势需要对方提供，我们不去轮询观远容器 |
| 4 | 拟用的 `COMPANY_BI_HOME`（持久化目录）和端口 | 脚本默认 `/data/companybi` 和 `30005`，不是这个就在命令行传 `CBI_HOME` / `CBI_PORT` |

## 1. 怎么跑（**脚本通过 stdin 送过去，塔上不会落任何文件**）

```bash
# 在你自己的电脑上执行，把输出保存在本地
ssh <user>@<tower> 'bash -s' < deploy/companybi/scripts/tower-readonly-check.sh | tee tower-report-$(date +%F).txt

# 如需指定目录/端口：
ssh <user>@<tower> 'CBI_HOME=/data/companybi CBI_PORT=30005 bash -s' < deploy/companybi/scripts/tower-readonly-check.sh | tee tower-report-$(date +%F).txt

# 账号不在 docker 组、且你同意 sudo -n（非交互，没权限就直接失败）做只读 docker 查询：
ssh <user>@<tower> 'CHECK_SUDO=1 bash -s' < deploy/companybi/scripts/tower-readonly-check.sh | tee tower-report-$(date +%F).txt

# 可选：多看一项 docker system df（只读，但磁盘大时要几十秒）
ssh <user>@<tower> 'DEEP=1 bash -s' < deploy/companybi/scripts/tower-readonly-check.sh | tee tower-report-$(date +%F).txt
```

## 2. 检查项与命令清单（对应你列的 7 个目标）

下面每条都只读。命令全文以脚本为准；这里列出**意图、命令、怎么判读**。

| 目标 | 命令（脚本第几节） | 判读 |
|---|---|---|
| **Docker Engine 版本** | `docker version --format …`、`docker info --format …`（§3） | 记录 Server 版本、存储驱动。我们的镜像 tar 是 Docker 29（containerd 镜像存储）导出的 OCI 布局；**只在 Docker 29 上验证过**。Server 版本低于 29 时一律先在个人服务器用**同版本**演练 `docker load`（见方案 A §3）。下文的"25"是我凭经验给的保守阈值（OCI 布局的原生导入大致从 25 开始），**未经验证**，不要当作结论 |
| **Compose 版本** | `docker compose version`、`docker-compose version`（§3） | 脚本只调用 **`docker compose`（v2 插件）**。只有旧的 `docker-compose` v1 → **不能直接用**，需要先决定装插件还是改脚本（这是写操作，不在本次范围） |
| **可用内存** | `free -m`、`/proc/meminfo`（MemAvailable）、`ps -eo pid,rss,comm`、`/proc/pressure/memory`（§4） | 默认预算：应用 2 GiB + MySQL 0.75 GiB。MemAvailable **< 3 GiB 判 FAIL，< 4 GiB 判 WARN**。注意这是"此刻"的值，必须结合观远的日内峰值再判断 |
| **Swap** | `swapon --show`、`swappiness`、OOM 历史：`dmesg` / `journalctl -k` 里的 `oom-kill`（§4） | 没有 swap 判 WARN。**有过 OOM-kill 记录** = 这台机器本来就紧，不建议放进来 |
| **磁盘与挂载点** | `df -hT`、`df -ih`、`lsblk`、Docker 根目录所在文件系统、`COMPANY_BI_HOME` 最近的已存在父目录（是否存在/属主/是否可写）（§5） | 可用 ≥ 8 GiB 为宜（约 0.6 GiB tar + 约 2 GiB 解包后镜像层 + 库 + 备份）；< 5 GiB 判 FAIL。**inode** 紧张同样要看。目录不可写 = 安装需要 root/sudo，要提前协调 |
| **拟用端口占用** | `ss -ltn 'sport = :30005'`、`ss -ltn`、30000–30100 区间占用（§6） | 30005 已监听 → FAIL。Company BI 只绑 `127.0.0.1:30005`，MySQL 不发布端口 |
| **既有容器运行情况** | `docker ps -a --format 'table 名称/镜像/状态/端口'`、`docker stats --no-stream`、`docker inspect --format 名称+重启次数+是否OOM+重启策略+内存上限`、`docker images`（§7） | **刻意不看**环境变量和启动参数（观远可能带密码）。`restarts` 高、`oom-killed=true` = 已有稳定性问题。是否已有名为 `companybi*` 的容器 → 有则脚本拒绝接管 |
| **镜像导入兼容性** | 只读部分：Docker 版本、存储驱动（`overlay2` 还是 containerd 快照）、`uname -m`（必须 `x86_64`）、Docker 根目录磁盘（§1、§3、§5） | **真正的 `docker load` 不能在只读检查里做**（会写入镜像库）。结论只能是"版本是否落在已验证范围内"，其余靠方案 A |

### 补充项（脚本也会看，原因都和部署风险直接相关）
| 项 | 原因 |
|---|---|
| Docker 网络已用子网 + `ip route` | Compose 会为 `companybi-net` 自动选下一个空闲子网。若 Docker 地址池与 ECS 的 VPC/内网路由重叠，新网络可能让**观远的出网/内网访问**出问题。发现重叠就要在 `compose.yaml` 里**先**固定子网，而不是装完再发现 |
| sshd `AllowTcpForwarding` | 访问方式是 `ssh -L 30005:127.0.0.1:30005`，被禁用则这个方式不可用 |
| `bash` 版本、`tar/gzip/sha256sum/awk/sed/stat/mktemp` | 运维脚本要求 bash ≥ 4.2 与这些命令 |
| 时区/时钟（`timedatectl`） | 应用按 `Asia/Shanghai` 运行，时钟偏差影响 token、日志和飞书排程 |
| `ulimit -n`、docker 组成员 | 权限与文件句柄 |

## 3. 这个脚本**不会做**什么（已由 `tests/static-audit.sh` 对脚本源码做了静态扫描）

- 不安装、不启动、不停止、不重启、不删除、不 prune、不 pull、不 load、不 run、不 exec、不 cp、不 build、不看容器日志。
- 不写磁盘、不重定向到文件、不改 sysctl/mount/iptables/systemd 状态。
- `docker inspect` 只带 `--format` 选取 名称/重启次数/OOM/重启策略/内存上限/子网，**不输出 `Config.Env`、`Cmd`、`Args`**。
- `ps` 只打印 `pid, rss, 命令名`，不带参数。
- `/etc/docker/daemon.json` 只输出白名单键（`log-driver、data-root、registry-mirrors、default-address-pools…`），不原样输出。
- 每个外部命令都 `</dev/null`，不会误吞 stdin 里的脚本。
- 唯一涉及 `sudo` 的地方是 `CHECK_SUDO=1` 时对 **docker 只读查询**加 `sudo -n`；默认不用 sudo。

## 4. 拿到输出后怎么决策

脚本最后一节会给出 FAIL/WARN 提示（**只是提示，由人决定**）。建议的决策表：

| 结果 | 含义 | 下一步 |
|---|---|---|
| 无 FAIL；MemAvailable ≥ 4 GiB；磁盘 ≥ 8 GiB；Docker ≥ 25 且 compose v2；无 OOM 记录 | 可进入"塔内安装"评审 | 先在个人服务器完成方案 A，再另行授权塔内安装 |
| Docker < 25 | `docker load` 兼容性未证明 | 先用**同版本** Docker 做方案 A §4；通不过就改用经典镜像库的引擎重新导出 tar，或改传方式 |
| 只有 docker-compose v1 | 脚本不可用 | 评审：装 compose 插件（写操作，需另行授权）还是写 v1 适配 |
| MemAvailable < 3 GiB，或存在 OOM-kill 历史 | 这台机器放不下 | **不要靠压缩 Company BI 来硬塞**；换机器，或先与观远负责人评估 |
| 无 swap + 内存 < 4 GiB | 风险高 | 同上，且明确"OOM 时被杀的可能是观远" |
| 子网与 VPC 路由重叠 | 网络冲突风险 | 在 compose 里固定不冲突的子网后再谈安装 |
| 30005 被占或已有 `companybi*` 容器 | 命名/端口冲突 | 换端口/项目名（只改 `.env`），或查清占用者 |

## 5. 执行后的要求
1. 报告原样保存在本地（含时间戳），**不要**把含观远容器清单的报告放进 Git 或发到公开渠道。
2. 报告里若意外出现密码/密钥样内容（理论上脚本不会输出），立即停止传播并告诉我。
3. 本次检查**不产生**任何服务器状态变化；如果发现某条命令改变了什么，视为事故，立刻停止并记录。
