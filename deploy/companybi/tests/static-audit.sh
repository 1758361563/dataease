#!/usr/bin/env bash
# Test F: static audit that the deploy tooling cannot reach beyond the Company BI stack.
# Pure text analysis: runs no docker command, needs no server.
#
#   tests/static-audit.sh        exit 0 = all rules satisfied
set -u   # no pipefail on purpose: `grep -q` closes pipes early and would make pipelines look failed
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 1
FAILS=0; SKIPS=0
ok()   { printf '  [PASS] %s\n' "$1"; }
bad()  { printf '  [FAIL] %s\n' "$1"; FAILS=$((FAILS+1)); }
skip() { printf '  [SKIP] %s\n' "$1"; SKIPS=$((SKIPS+1)); }
# strip comment-only lines so documentation of forbidden commands does not trip the audit
code() { grep -v -E '^[[:space:]]*#' "$1"; }
TOWER=scripts/tower-readonly-check.sh      # read-only inspector: audited separately (section 5), not by the "operations" rules
SCRIPTS=()
for f in scripts/*.sh image/*.sh; do [ "$f" = "$TOWER" ] || SCRIPTS+=("$f"); done

rule_absent() { # rule_absent "<description>" "<ERE>"   over all non-comment script lines
  local desc="$1" re="$2" hits="" f h
  for f in "${SCRIPTS[@]}"; do h="$(code "$f" | grep -n -E "$re" || true)"; [ -z "$h" ] || hits+="$f: $h"$'\n'; done
  if [ -z "$hits" ]; then ok "$desc"; else bad "$desc"; printf '%s' "$hits" | sed 's/^/           /'; fi
}

echo "== 1. forbidden docker / host operations in scripts =="
rule_absent "no system/volume/network/image/container prune"          'docker[[:space:]]+(system|volume|network|image|container|builder)[[:space:]]+prune|docker[[:space:]]+prune'
rule_absent "no docker volume / network create-remove"                'docker[[:space:]]+(volume|network)[[:space:]]+(rm|create|disconnect)'
rule_absent "no docker rm / rmi / kill"                               'docker[[:space:]]+(rm|rmi|kill)[[:space:]]|docker[[:space:]]+(container|image)[[:space:]]+rm'
rule_absent "no docker compose down / rm / kill"                      'compose[^|;&]*[[:space:]](down|rm|kill)([[:space:]]|$)'
rule_absent "no --remove-orphans / -v / --volumes passed to compose"  'compose[^|;&]*(--remove-orphans|--volumes)'
rule_absent "no \"docker ps\" / \"docker ps -a\" used as a selector"  'docker[[:space:]]+(container[[:space:]]+)?ps[[:space:]]+(-a|-q|-aq|--all|--quiet)'
rule_absent "no command substitution feeding docker stop/start/exec"  'docker[[:space:]]+(stop|start|restart|exec|rm)[^|;&]*\$\('
rule_absent "no docker daemon / host-global config changes"           'daemon\.json|/etc/docker|systemctl|service[[:space:]]+docker|dockerd|iptables|ufw|firewall-cmd|sysctl|setenforce'
rule_absent "no broad process killing"                                'pkill|killall|kill[[:space:]]+-9|xargs[[:space:]]+(-r[[:space:]]+)?docker'
rule_absent "no official DataEase installer / dectl"                  'dectl|install\.sh[[:space:]].*dataease|dataease-installer|get\.dataease|installer/'
rule_absent "no latest tag"                                           'companybi:latest|[^a-z]:latest'
rule_absent "no registry pull/push (images arrive as tar)"            'docker[[:space:]]+(push|login)'

echo "== 2. every mutating docker call names only <project> containers =="
printf '  (listing for review)\n'
for f in scripts/*.sh; do code "$f" | grep -n -E 'docker[[:space:]]+(stop|start|restart|exec|cp|run|load|save)' | sed "s|^|           $f:|"; done
bad_targets="$(for f in scripts/*.sh; do code "$f" | grep -E 'docker[[:space:]]+(stop|start|restart)[[:space:]]' | grep -v -E '"\$APP_C"|"\$DB_C"' ; done)"
if [ -z "$bad_targets" ]; then ok "docker stop/start/restart only target \$APP_C / \$DB_C"; else bad "docker stop/start/restart with other targets:"; printf '%s\n' "$bad_targets" | sed 's/^/           /'; fi
bad_exec="$(for f in scripts/*.sh; do code "$f" | grep -E 'docker[[:space:]]+exec' | grep -v -E '"\$DB_C"|"\$APP_C"' ; done)"
if [ -z "$bad_exec" ]; then ok "docker exec only targets \$DB_C / \$APP_C"; else bad "docker exec with other targets:"; printf '%s\n' "$bad_exec" | sed 's/^/           /'; fi
if code scripts/lib.sh | grep -q 'assert_ours()'; then ok "lib.sh refuses same-named containers owned by another compose project (assert_ours)"; else bad "assert_ours missing"; fi
if code scripts/lib.sh | grep -q 'case "\$verb" in config|ps|logs|up|stop|start|restart)'; then ok "compose() whitelists verbs: config ps logs up stop start restart"; else bad "compose() verb whitelist missing/changed"; fi
if code scripts/lib.sh | grep -q -E 'PROJECT" =~ \^\[a-z\]'; then ok "project name is validated"; else bad "project-name validation missing"; fi

echo "== 3. compose.yaml isolation =="
Y="$(code compose.yaml)"
chk() { # chk "<desc>" <expect: absent|present> "<ERE>"
  if printf '%s\n' "$Y" | grep -q -E "$3"; then [ "$2" = present ] && ok "$1" || bad "$1"; else [ "$2" = absent ] && ok "$1" || bad "$1"; fi
}
chk "container names carry the project prefix"        present 'container_name: \$\{COMPANY_BI_PROJECT:-companybi\}-(app|mysql)'
chk "dedicated network named after the project"       present 'name: \$\{COMPANY_BI_PROJECT:-companybi\}-net'
chk "no external network / volume"                    absent  'external:'
chk "no host network"                                 absent  'network_mode'
chk "no privileged / host pid / host ipc"             absent  'privileged|pid: *host|ipc: *host|cap_add'
chk "docker.sock not mounted"                         absent  'docker\.sock'
chk "no named docker volumes section"                 absent  '^volumes:'
chk "MySQL publishes no port"                         absent  '3306:'
chk "app port bound to loopback by default"           present '\$\{COMPANY_BI_BIND_ADDR:-127\.0\.0\.1\}'
chk "bind mounts only under COMPANY_BI_HOME / ./mysql" absent  '^[[:space:]]+- (/|\.\./)(etc|var|usr|root|home|opt|proc|sys)'
chk "integration executor bundled with app"          present 'COMPANY_BI_INTEGRATION_WORKER_DIR: "/opt/apps/integration-worker"'
chk "memory/cpu limits present for both services"     present 'mem_limit:'
chk "no :latest image"                                absent  'image:.*:latest'
chk "app image tag comes from the version file"       present 'image: companybi:\$\{COMPANY_BI_VERSION:\?'
chk "no hard-coded production datasource"             absent  '39\.101\.192\.183|Password123'

echo "== 4. secrets and Git hygiene =="
if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  if git ls-files --error-unmatch .env >/dev/null 2>&1; then bad ".env is tracked by git"; else ok ".env is not tracked"; fi
  if git check-ignore -q .env 2>/dev/null; then ok ".env is git-ignored"; else bad ".env is not git-ignored"; fi
  if git check-ignore -q dist/x.tar 2>/dev/null && git check-ignore -q backups/x.sql.gz 2>/dev/null; then ok "tars and sql dumps are git-ignored"; else bad "tar/sql not ignored"; fi
  leak="$(git grep -n -I -E 'Password123|39\.101\.192\.183' -- . 2>/dev/null | grep -v -E 'tests/static-audit.sh|tests/local-e2e.sh|image/sanitize-standalone-yml.sh|image/verify-jar.sh' || true)"
  if [ -z "$leak" ]; then ok "no known production DB secret/host in tracked deploy files"; else bad "known secret/host found:"; printf '%s\n' "$leak" | sed 's/^/           /'; fi
else
  skip "git checks (.env ignored/untracked, dumps ignored, secret scan of tracked files): git cannot read this work tree from here; run the audit from Git Bash / the build machine"
fi
if code scripts/*.sh | grep -n -E 'echo[^|]*\$\{?COMPANY_BI_(DB_PASSWORD|DB_ROOT_PASSWORD|TOKEN_SECRET|BOOTSTRAP_PASSWORD)' | grep -q .; then bad "a script echoes a secret variable"; else ok "no script echoes a secret variable"; fi
if grep -q -E 'MYSQL_PWD="\$MYSQL_ROOT_PASSWORD"' scripts/lib.sh && ! code scripts/*.sh | grep -q -E 'mysql[^|]* -p[^ ]'; then ok "DB password is passed via in-container env, never on a command line"; else bad "possible DB password on command line"; fi

echo "== 5. tower read-only inspector (scripts/tower-readonly-check.sh) can only READ =="
if [ -f "$TOWER" ]; then
  TC="$(code "$TOWER")"
  t_absent() { # t_absent "<desc>" "<ERE>"
    local h; h="$(printf '%s\n' "$TC" | grep -n -E "$2" || true)"
    if [ -z "$h" ]; then ok "$1"; else bad "$1"; printf '%s\n' "$h" | sed 's/^/           /'; fi
  }
  t_absent "no mutating docker verb (run/exec/cp/build/start/stop/rm/rmi/pull/load/logs/prune/...)" '(^|[;&|(`]|\$\(|do |then |rs "|bash -c ")[[:space:]]*(docker|D|dr)[[:space:]]+(run|exec|cp|build|create|start|stop|restart|rm|rmi|kill|pause|unpause|pull|push|load|save|import|export|commit|tag|login|logout|prune|logs|attach|update|rename|events)([[:space:]]|$)'
  t_absent "no mutating docker sub-command (network/volume/image/container/system/builder create|rm|prune|load|...)" '(^|[;&|(`]|\$\(|do |then |rs "|bash -c ")[[:space:]]*(docker|D|dr)[[:space:]]+(network|volume|image|container|system|builder)[[:space:]]+(create|rm|prune|connect|disconnect|load|import|build|pull|push|tag|save)'
  t_absent "no docker compose state change" 'compose[[:space:]]+(up|down|start|stop|restart|rm|pull|build|create|kill|run|exec|cp)([[:space:]]|$)'
  t_absent "no host mutation at command position (rm/mv/cp/chmod/mkdir/touch/tee/dd/kill/reboot/yum/apt/pip/npm/curl/wget/iptables/sysctl/mount/crontab/...)" '(^|[;&|(`]|\$\(|do |then |rs "|bash -c ")[[:space:]]*(rm|mv|cp|chmod|chown|mkdir|touch|tee|dd|ln|kill|pkill|killall|reboot|shutdown|halt|yum|dnf|apt|apt-get|pip|pip3|npm|curl|wget|iptables|ufw|firewall-cmd|sysctl|mount|umount|swapoff|useradd|usermod|passwd|crontab|setenforce|ssh-keygen|scp|rsync|nc|ncat)([[:space:]]|$)'
  t_absent "no systemctl/service state change" 'systemctl[[:space:]]+(start|stop|restart|reload|enable|disable|mask|unmask|daemon-reload|kill|set-)|service[[:space:]]+[a-z]+[[:space:]]+(start|stop|restart)'
  t_absent "no in-place edit (sed -i)" 'sed[[:space:]]+(-[a-zA-Z]*i|--in-place)'
  # redirects: only 2>&1, </dev/null, >/dev/null, 2>/dev/null are allowed
  RED="$(printf '%s\n' "$TC" | sed -E 's#2>&1##g; s#</dev/null##g; s#[0-9]?>/dev/null##g; s#>&[0-9]##g' | grep -n -E '(^|[[:space:]]|[0-9])>>?[[:space:]]*[A-Za-z0-9_./$"~]' | grep -v -E '<each[^>]*>' || true)"
  if [ -z "$RED" ]; then ok "no redirect into a file (only /dev/null and 2>&1)"; else bad "output redirect found"; printf '%s\n' "$RED" | sed 's/^/           /'; fi
  INS="$(printf '%s\n' "$TC" | grep -n -E 'inspect' | grep -v -E '^\S*\s*echo ' || true)"
  BADINS="$(printf '%s\n' "$INS" | grep -v -E '(--format|-f )' | grep . || true)"
  LEAKINS="$(printf '%s\n' "$INS" | grep -E '\bEnv\b|\.Args|\.Cmd|Entrypoint|Config\.(Env|Cmd|Labels)|json \.|\.Mounts|\.NetworkSettings' | grep . || true)"
  if [ -z "$BADINS" ] && [ -z "$LEAKINS" ]; then ok "every docker inspect uses a --format that selects safe fields only (no Config/Env/Args/Cmd)"; else bad "docker inspect without --format or touching env/args:"; printf '%s\n%s\n' "$BADINS" "$LEAKINS" | sed 's/^/           /'; fi
  SUD="$(printf '%s\n' "$TC" | grep -n -E '(^|[;&|(`]|\$\(|do |then |rs "|bash -c ")[[:space:]]*sudo[[:space:]]' | grep -v -E 'SUDO=' || true)"
  if [ -z "$SUD" ]; then ok "sudo appears only as the optional 'sudo -n' prefix for docker queries (CHECK_SUDO=1)"; else bad "unexpected sudo use"; printf '%s\n' "$SUD" | sed 's/^/           /'; fi
  PSA="$(printf '%s\n' "$TC" | grep -n -E '(^|[;&|(`]|\$\(|do |then |rs "|bash -c ")[[:space:]]*ps[[:space:]]' | grep -v -E 'ps -eo pid,rss,comm' || true)"
  if [ -z "$PSA" ]; then ok "ps prints only pid/rss/command name (no argument lists that could carry secrets)"; else bad "ps with a wider format"; printf '%s\n' "$PSA" | sed 's/^/           /'; fi
  if printf '%s\n' "$TC" | grep -q -E 'cat[[:space:]]+/etc/docker/daemon\.json|<[[:space:]]*/etc/docker/daemon\.json'; then bad "daemon.json is printed verbatim"; else ok "daemon.json is only shown through a key whitelist"; fi
  NSTD="$(printf '%s\n' "$TC" | grep -c '</dev/null' || true)"
  if [ "${NSTD:-0}" -ge 6 ] && printf '%s\n' "$TC" | grep -q -E '^r\(\) .*</dev/null' && printf '%s\n' "$TC" | grep -q -E '^rs\(\) .*</dev/null'; then ok "wrappers r()/rs()/D() close stdin, so no command can swallow the script piped over ssh"; else bad "stdin is not closed for every command"; fi
  if bash -n "$TOWER" 2>/dev/null; then ok "tower script parses (bash -n)"; else bad "tower script has a syntax error"; fi
else
  bad "$TOWER is missing"
fi

echo
[ "$SKIPS" -eq 0 ] || echo "($SKIPS check group(s) skipped - see [SKIP] lines)"
if [ "$FAILS" -eq 0 ]; then echo "STATIC AUDIT: PASS"; exit 0; fi
echo "STATIC AUDIT: FAIL ($FAILS)"; exit 1
