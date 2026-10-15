#!/usr/bin/env bash
# READ-ONLY inspection of a Docker host before Company BI is installed on it (made for the Aliyun
# "聚石塔" ECS that also runs the production Guanyuan BI).
#
# HOW TO RUN (nothing is written on the server: the script travels over stdin):
#     ssh <user>@<tower> 'bash -s' < tower-readonly-check.sh | tee tower-report-$(date +%F).txt
#   optional environment, given on the ssh command line:
#     ssh <user>@<tower> 'CBI_HOME=/data/companybi CBI_PORT=30005 bash -s' < tower-readonly-check.sh | tee ...
#     ssh ... 'CHECK_SUDO=1 bash -s' ...   run ONLY the docker queries below through `sudo -n` (non-interactive)
#     ssh ... 'DEEP=1 bash -s' ...         also run `docker system df` (read-only but can take a while)
#
# WHAT IT DOES NOT DO (enforced by tests/static-audit.sh, which greps this very file):
#   * no install / start / stop / restart / remove / prune / pull / load / run / exec / cp / build / logs;
#   * no write to disk, no redirect into files, no sysctl/mount/iptables/systemctl state change;
#   * `docker inspect` only with a --format that selects name/restart/OOM/subnet fields - never Config.Env,
#     never Cmd/Args, so no Guanyuan secret can leak into the report;
#   * `ps` prints only pid/rss/command NAME (no arguments);
#   * /etc/docker/daemon.json is shown through a key whitelist, not verbatim.
# Every external command gets </dev/null so it can never swallow the rest of this script from stdin.
export LC_ALL=C
CBI_HOME="${CBI_HOME:-/data/companybi}"
CBI_PORT="${CBI_PORT:-30005}"
CBI_PROJECT="${CBI_PROJECT:-companybi}"
CHECK_SUDO="${CHECK_SUDO:-0}"
DEEP="${DEEP:-0}"

SUDO=""; [ "$CHECK_SUDO" = 1 ] && SUDO="sudo -n"
sec() { printf '\n==================== %s ====================\n' "$*"; }
# r <cmd...>: show and run one command, stdin closed, stderr merged, lines capped
r() { printf '$ %s\n' "$*"; "$@" </dev/null 2>&1 | cut -c1-260; printf '\n'; }
# rs '<shell pipeline>': same, for pipelines (the string is what gets audited)
rs() { printf '$ %s\n' "$1"; bash -c "$1" </dev/null 2>&1 | cut -c1-260; printf '\n'; }
have() { command -v "$1" >/dev/null 2>&1; }
D() { # docker wrapper (optionally via sudo -n); stdin closed
  if [ -n "$SUDO" ]; then $SUDO docker "$@" </dev/null 2>&1; else docker "$@" </dev/null 2>&1; fi
}
dr() { printf '$ %sdocker %s\n' "${SUDO:+$SUDO }" "$*"; D "$@" | cut -c1-260; printf '\n'; }
FLAG_FILE_NOTE="(findings are collected into the verdict section at the end; they are hints, not decisions)"
FINDINGS=""
note() { FINDINGS="${FINDINGS}$1"$'\n'; }

sec "0. who/when (this report was produced read-only)"
r date '+%F %T %Z'
r uptime
r id
echo "CBI_HOME=$CBI_HOME CBI_PORT=$CBI_PORT CBI_PROJECT=$CBI_PROJECT CHECK_SUDO=$CHECK_SUDO DEEP=$DEEP"

sec "1. OS / kernel / CPU / architecture"
rs "grep -E '^(PRETTY_NAME|VERSION_ID)=' /etc/os-release"
r uname -srm
r nproc
ARCH="$(uname -m 2>/dev/null)"; [ "$ARCH" = x86_64 ] || note "FAIL arch is '$ARCH': the image is linux/amd64 only"
rs "command -v getenforce >/dev/null && getenforce || echo 'getenforce not installed (SELinux unknown/off)'"
rs "stat -fc 'cgroup fs type: %T' /sys/fs/cgroup"

sec "2. tools the Company BI scripts need on the server"
BV="$(bash -c 'echo ${BASH_VERSINFO[0]}.${BASH_VERSINFO[1]}' </dev/null 2>/dev/null)"
echo "bash $BV (scripts need >= 4.2)"
case "$BV" in 4.[0-1]|[0-3].*) note "FAIL bash $BV is older than 4.2" ;; esac
for c in docker tar gzip gunzip sha256sum awk sed grep stat mktemp ss; do printf '%-10s %s\n' "$c" "$(command -v $c </dev/null 2>/dev/null || echo MISSING)"; done
for c in docker tar gzip sha256sum awk sed grep stat mktemp; do have $c || note "FAIL required command missing: $c"; done

sec "3. Docker Engine / Compose"
if have docker; then
  dr version --format 'client={{.Client.Version}} api={{.Client.APIVersion}} | server={{.Server.Version}} api={{.Server.APIVersion}} {{.Server.Os}}/{{.Server.Arch}}'
  dr info --format 'storage-driver={{.Driver}} root={{.DockerRootDir}} cgroup={{.CgroupDriver}}/v{{.CgroupVersion}} logging={{.LoggingDriver}} live-restore={{.LiveRestoreEnabled}}'
  dr info --format 'containers={{.Containers}} running={{.ContainersRunning}} images={{.Images}} mem-total={{.MemTotal}} ncpu={{.NCPU}}'
  dr info --format 'driver-status={{json .DriverStatus}}'
  dr compose version
  rs "docker-compose version 2>&1 | head -1"
  SV="$(D version --format '{{.Server.Version}}' | head -1)"; CV="$(D compose version --short | head -1)"
  echo "server=$SV compose=$CV"
  case "$SV" in ''|*rror*|*ermission*|*annot*) note "FAIL cannot query the Docker daemon as this user (not in the docker group?). Re-run with CHECK_SUDO=1 if sudo -n is permitted, or ask for access" ;; esac
  case "$CV" in v2.*|2.*|v[3-9].*|[3-9].*|v[1-9][0-9]*.*) : ;; *) note "FAIL 'docker compose' (v2 plugin) not available: the scripts call 'docker compose', not 'docker-compose'" ;; esac
  rs "systemctl is-active docker; systemctl is-enabled docker"
else
  note "FAIL docker is not installed or not on PATH"
fi
echo "-- /etc/docker/daemon.json (whitelisted keys only):"
if [ -r /etc/docker/daemon.json ] && have python3; then
  python3 -c '
import json
try:
    d = json.load(open("/etc/docker/daemon.json"))
except Exception as e:
    print("unreadable/invalid:", e); raise SystemExit
keep = ["log-driver","log-opts","data-root","registry-mirrors","default-address-pools","bip","live-restore","storage-driver","iptables","userland-proxy","features","default-ulimits"]
print(json.dumps({k: d[k] for k in keep if k in d}, indent=1, ensure_ascii=False))
' </dev/null 2>&1 | cut -c1-200
else echo "(not readable by this user, or python3 missing - skipped)"; fi
echo

sec "4. memory / swap / OOM history (the budget is app 2 GiB + MySQL 0.75 GiB + OS headroom)"
r free -m
rs "grep -E '^(MemTotal|MemAvailable|SwapTotal|SwapFree|Dirty|Committed_AS):' /proc/meminfo"
rs "swapon --show 2>&1 | head -5; echo \"swappiness=\$(cat /proc/sys/vm/swappiness)\""
rs "test -r /proc/pressure/memory && cat /proc/pressure/memory || echo 'PSI not available'"
r vmstat 1 5
rs "ps -eo pid,rss,comm --sort=-rss | head -12"
rs "dmesg -T 2>&1 | grep -i -E 'out of memory|oom-kill|killed process' | tail -5; true"
rs "journalctl -k --no-pager --since '14 days ago' 2>&1 | grep -i -E 'out of memory|oom-kill|killed process' | tail -5; true"
MA="$(awk '/^MemAvailable:/{printf "%d", $2/1024}' /proc/meminfo 2>/dev/null)"
if [ -n "$MA" ]; then
  echo "MemAvailable right now: ${MA} MiB"
  if [ "$MA" -lt 3072 ]; then note "FAIL MemAvailable ${MA} MiB < 3072: the default limits (2 GiB + 768 MiB) do not fit; lower them or do not deploy here"
  elif [ "$MA" -lt 4096 ]; then note "WARN MemAvailable ${MA} MiB < 4096: tight; sample again at the daily peak before deciding"; fi
fi
ST="$(awk '/^SwapTotal:/{printf "%d", $2/1024}' /proc/meminfo 2>/dev/null)"; [ "${ST:-0}" = 0 ] && note "WARN no swap configured: an OOM kill has no cushion (it may kill Guanyuan, not us, if memory runs out)"

sec "5. disk / inodes / mounts"
rs "df -hT -x tmpfs -x devtmpfs -x overlay -x squashfs 2>&1"
rs "df -ih -x tmpfs -x devtmpfs -x overlay -x squashfs 2>&1"
r lsblk -o NAME,SIZE,TYPE,FSTYPE,MOUNTPOINT
if have docker; then
  DR="$(D info --format '{{.DockerRootDir}}' | head -1)"; [ -n "$DR" ] && rs "df -h '$DR' 2>&1; echo '(docker root = $DR)'"
fi
P="$CBI_HOME"; while [ ! -e "$P" ] && [ "$P" != / ]; do P="$(dirname "$P")"; done
echo "candidate COMPANY_BI_HOME=$CBI_HOME  (exists: $([ -e "$CBI_HOME" ] && echo yes || echo no); nearest existing parent: $P)"
rs "df -hT '$P' 2>&1; stat -c 'parent owner=%U:%G mode=%a' '$P' 2>&1; test -w '$P' && echo 'writable by this user' || echo 'NOT writable by this user (install needs root/sudo or another path)'"
FREE_GB="$(df -Pk "$P" 2>/dev/null | awk 'NR==2{printf "%d", $4/1024/1024}')"
if [ -n "$FREE_GB" ]; then
  echo "free on that filesystem: ${FREE_GB} GiB (need: ~0.6 GiB tars + ~2 GiB unpacked image layers [companybi 0.9 GiB + mysql 1.1 GiB] + DB + backups; plan >= 8 GiB)"
  [ "$FREE_GB" -lt 5 ] && note "FAIL only ${FREE_GB} GiB free where Company BI would live (< 5)"
  { [ "$FREE_GB" -ge 5 ] && [ "$FREE_GB" -lt 8 ]; } && note "WARN ${FREE_GB} GiB free (< 8): works, but backups will not have room to grow"
fi
[ -e "$CBI_HOME" ] && note "WARN $CBI_HOME already exists - list it (ls -la) before installing; install.sh refuses a non-empty mysql/data"

sec "6. ports / networks (Company BI binds 127.0.0.1:$CBI_PORT only; MySQL publishes nothing)"
rs "ss -ltn 2>&1 | head -60"
rs "ss -ltn 'sport = :$CBI_PORT' 2>&1"
PORT_BUSY="$(ss -ltn "sport = :$CBI_PORT" 2>/dev/null </dev/null | grep -c LISTEN)"
[ "${PORT_BUSY:-0}" -gt 0 ] && note "FAIL port $CBI_PORT is already listening"
rs "ss -ltn 2>/dev/null | awk 'NR>1{n=split(\$4,a,\":\"); p=a[n]; if (p>=30000 && p<=30100) print \"30000-30100 range in use: \" \$4}'"
r ip -br addr
r ip route
rs "echo ip_forward=\$(cat /proc/sys/net/ipv4/ip_forward)"
if have docker; then
  rs "docker network ls --format 'table {{.Name}}\t{{.Driver}}\t{{.Scope}}' </dev/null 2>&1"
  echo '$ docker network inspect <each> --format name+subnet only'
  for n in $(D network ls -q); do D network inspect "$n" --format '{{.Name}} subnets={{range .IPAM.Config}}{{.Subnet}} {{end}}'; done | cut -c1-200
  echo
  echo "(a new compose network gets the next free subnet from Docker's pool; if the pool overlaps the ECS VPC/intranet routes above,"
  echo " pin a subnet in compose BEFORE installing - do not discover it by breaking Guanyuan's connectivity)"
fi
rs "grep -i -E '^[[:space:]]*(AllowTcpForwarding|GatewayPorts|PermitOpen|PasswordAuthentication)' /etc/ssh/sshd_config /etc/ssh/sshd_config.d/*.conf 2>&1 | head"
echo "(AllowTcpForwarding must not be 'no' for the documented 'ssh -L ${CBI_PORT}:127.0.0.1:${CBI_PORT}' access)"

sec "7. what already runs (names / images / status / published ports ONLY - no env, no command lines)"
if have docker; then
  dr ps -a --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}'
  dr stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}'
  echo '$ docker inspect <each container> --format name/restarts/oom/restart-policy only'
  for c in $(D ps -aq); do D inspect "$c" --format '{{.Name}} restarts={{.RestartCount}} oom-killed={{.State.OOMKilled}} policy={{.HostConfig.RestartPolicy.Name}} mem-limit={{.HostConfig.Memory}}'; done | cut -c1-200
  echo
  dr images --format 'table {{.Repository}}\t{{.Tag}}\t{{.ID}}\t{{.Size}}'
  rs "echo \"docker volumes: \$(docker volume ls -q </dev/null 2>/dev/null | wc -l)\""
  [ "$DEEP" = 1 ] && dr system df
  CLASH="$(D ps -a --format '{{.Names}}' | grep -c -E "^(${CBI_PROJECT}|cbitest|cbirel)")"
  [ "${CLASH:-0}" -gt 0 ] && note "FAIL a container named like ${CBI_PROJECT}* already exists - the scripts refuse to touch it"
  IMGCLASH="$(D images --format '{{.Repository}}' | grep -c -x 'companybi')"
  [ "${IMGCLASH:-0}" -gt 0 ] && note "WARN an image repository 'companybi' already exists"
  OOM="$(for c in $(D ps -aq); do D inspect "$c" --format '{{.State.OOMKilled}}'; done | grep -c true)"
  [ "${OOM:-0}" -gt 0 ] && note "WARN $OOM container(s) were OOM-killed in the past: memory on this host is already tight"
fi

sec "8. time / limits / access"
rs "timedatectl 2>&1 | head -6"
rs "echo nofile=\$(ulimit -n)"
rs "getent group docker 2>&1"
r groups

sec "9. verdict hints (advisory; a human decides)"
echo "$FLAG_FILE_NOTE"
if [ -z "$FINDINGS" ]; then echo "no FAIL/WARN hints raised"; else printf '%s' "$FINDINGS"; fi
echo
echo "NOT checked here (needs a decision or a write, so it is out of scope for a read-only pass):"
echo "  - whether 'docker load' of our tar works on THIS engine (do it first on a personal server running the same Docker version)"
echo "  - real memory/disk use of Company BI under load; behaviour of the upgrade scripts on this host"
echo "  - Guanyuan's own memory trend over a business day (ask its owner / use existing monitoring, do not poll its containers)"
echo "== end of read-only report =="
