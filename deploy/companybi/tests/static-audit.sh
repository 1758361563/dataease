#!/usr/bin/env bash
# Test F: static audit that the deploy tooling cannot reach beyond the Company BI stack.
# Pure text analysis: runs no docker command, needs no server.
#
#   tests/static-audit.sh        exit 0 = all rules satisfied
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 1
FAILS=0
ok()   { printf '  [PASS] %s\n' "$1"; }
bad()  { printf '  [FAIL] %s\n' "$1"; FAILS=$((FAILS+1)); }
# strip comment-only lines so documentation of forbidden commands does not trip the audit
code() { grep -v -E '^[[:space:]]*#' "$1"; }
SCRIPTS=(scripts/*.sh image/*.sh)

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
chk "Feishu dry-run hard-coded true"                  present 'COMPANY_FEISHU_DRY_RUN: "true"'
chk "memory/cpu limits present for both services"     present 'mem_limit:'
chk "no :latest image"                                absent  'image:.*:latest'
chk "app image tag comes from the version file"       present 'image: companybi:\$\{COMPANY_BI_VERSION:\?'
chk "no hard-coded production datasource"             absent  '39\.101\.192\.183|Password123'

echo "== 4. secrets and Git hygiene =="
if git ls-files --error-unmatch .env >/dev/null 2>&1; then bad ".env is tracked by git"; else ok ".env is not tracked"; fi
if git check-ignore -q .env 2>/dev/null; then ok ".env is git-ignored"; else bad ".env is not git-ignored"; fi
if git check-ignore -q dist/x.tar 2>/dev/null && git check-ignore -q backups/x.sql.gz 2>/dev/null; then ok "tars and sql dumps are git-ignored"; else bad "tar/sql not ignored"; fi
leak="$(git grep -n -I -E 'Password123|39\.101\.192\.183' -- . 2>/dev/null | grep -v -E 'tests/static-audit.sh|tests/local-e2e.sh|image/sanitize-standalone-yml.sh|image/verify-jar.sh' || true)"
if [ -z "$leak" ]; then ok "no known production DB secret/host in deploy files"; else bad "known secret/host found:"; printf '%s\n' "$leak" | sed 's/^/           /'; fi
if code scripts/*.sh | grep -n -E 'echo[^|]*\$\{?COMPANY_BI_(DB_PASSWORD|DB_ROOT_PASSWORD|TOKEN_SECRET|BOOTSTRAP_PASSWORD)' | grep -q .; then bad "a script echoes a secret variable"; else ok "no script echoes a secret variable"; fi
if grep -q -E 'MYSQL_PWD="\$MYSQL_ROOT_PASSWORD"' scripts/lib.sh && ! code scripts/*.sh | grep -q -E 'mysql[^|]* -p[^ ]'; then ok "DB password is passed via in-container env, never on a command line"; else bad "possible DB password on command line"; fi

echo
if [ "$FAILS" -eq 0 ]; then echo "STATIC AUDIT: PASS"; exit 0; fi
echo "STATIC AUDIT: FAIL ($FAILS)"; exit 1
