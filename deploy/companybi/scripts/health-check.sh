#!/usr/bin/env bash
# Health check for the Company BI stack. Read-only.
#
#   health-check.sh [--wait SECONDS] [--expect-version V]
#
# exit 0  = every check passed; exit 1 = at least one failed (details printed).
# Checks: containers up/healthy, no OOM/restart loop, HTTP 200 on the UI, a DB-backed public API,
#         admin row present + no failed Flyway migration, no fatal startup errors in the app log,
#         (optional) the running image is the expected version.
set -uo pipefail
# shellcheck source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

WAIT=0; EXPECT=""
while [ $# -gt 0 ]; do
  case "$1" in
    --wait) WAIT="${2:?}"; shift 2 ;;
    --expect-version) EXPECT="${2:?}"; shift 2 ;;
    -h|--help) sed -n '2,10p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
load_env
need_cmds docker awk sed grep
docker info >/dev/null 2>&1 || die "docker daemon is not reachable"

HOST="$BIND"; [ "$HOST" = "0.0.0.0" ] && HOST="127.0.0.1"
BASE="http://$HOST:$PORT"
FATAL_RE='APPLICATION FAILED TO START|Application run failed|java\.lang\.OutOfMemoryError|FlywayException|Migration of schema .* failed|Unable to start web server|Failed to initialize company|CompanyBiSchemaInitializer.*(fail|error)'

FAILS=0; OUT=""
pass() { OUT+="  [PASS] $1"$'\n'; }
fail() { OUT+="  [FAIL] $1"$'\n'; FAILS=$((FAILS+1)); }
redact() { sed -E 's/((password|passwd|secret|token)[=: "]+)[^ ",;&]+/\1***/Ig' | cut -c1-220; }

run_checks() {
  FAILS=0; OUT=""
  local st h oom rc ver code body n

  # 1 containers
  for c in "$DB_C" "$APP_C"; do
    if ! container_exists "$c"; then fail "$c: container does not exist"; continue; fi
    if [ "$(container_project "$c")" != "$PROJECT" ]; then fail "$c: belongs to another compose project"; continue; fi
    st="$(docker inspect -f '{{.State.Status}}' "$c")"; h="$(container_health "$c")"
    oom="$(docker inspect -f '{{.State.OOMKilled}}' "$c")"; rc="$(docker inspect -f '{{.RestartCount}}' "$c")"
    if [ "$st" = running ] && [ "$h" = healthy ]; then pass "$c: running, healthy (restarts=$rc)"
    else fail "$c: status=$st health=$h restarts=$rc oom=$oom"; fi
    [ "$oom" = true ] && fail "$c: was OOM-killed (raise COMPANY_BI_*_MEM_LIMIT only after checking host memory)"
    [ "${rc:-0}" -gt 3 ] && fail "$c: restarted $rc times (restart loop?)"
  done

  # 2 expected version
  if [ -n "$EXPECT" ] && container_exists "$APP_C"; then
    ver="$(docker inspect -f '{{index .Config.Labels "com.companybi.version"}}' "$APP_C" 2>/dev/null)"
    if [ "$ver" = "$EXPECT" ]; then pass "running version = $ver"; else fail "running version '$ver' != expected '$EXPECT'"; fi
  fi

  # 3 HTTP
  code="$(http_code "$BASE/")"
  if [ "$code" = 200 ]; then pass "GET $BASE/ -> 200"; else fail "GET $BASE/ -> $code"; fi
  body="$(http_body "$BASE/de2api/sysParameter/ui")"
  case "$body" in
    *'"code":0'*) pass "GET /de2api/sysParameter/ui -> code 0 (backend + metadata DB reachable)" ;;
    *) fail "GET /de2api/sysParameter/ui did not return code 0" ;;
  esac

  # 4 metadata DB facts
  if container_running "$DB_C"; then
    n="$(mysql_exec "SELECT COUNT(*) FROM \`$DB_NAME\`.company_bi_user WHERE id=1;" 2>/dev/null | tr -d '[:space:]')"
    if [ "$n" = 1 ]; then pass "company_bi_user id=1 (bootstrap admin) present"; else fail "company_bi_user id=1 missing (n='${n:-?}')"; fi
    n="$(mysql_exec "SELECT COUNT(*) FROM \`$DB_NAME\`.de_standalone_version WHERE success=0;" 2>/dev/null | tr -d '[:space:]')"
    if [ "$n" = 0 ]; then pass "flyway: no failed migration"; else fail "flyway: failed migrations present (n='${n:-?}')"; fi
  else
    fail "MySQL not running; DB facts not checked"
  fi

  # 5 fatal log lines since this container start
  if container_exists "$APP_C"; then
    since="$(docker inspect -f '{{.State.StartedAt}}' "$APP_C")"
    hits="$(docker logs --since "$since" "$APP_C" 2>&1 | grep -E "$FATAL_RE" | head -3 | redact || true)"
    if [ -z "$hits" ]; then pass "no fatal startup errors in app log since $since"
    else fail "fatal pattern in app log:"$'\n'"$(printf '%s\n' "$hits" | sed 's/^/           /')"; fi
  fi
  return $FAILS
}

end=$(( $(date +%s) + WAIT ))
while :; do
  run_checks && break
  [ "$(date +%s)" -ge "$end" ] && break
  # a crash-looping app will not heal by waiting: stop early
  if container_exists "$APP_C" && [ "$(docker inspect -f '{{.RestartCount}}' "$APP_C" 2>/dev/null || echo 0)" -ge 3 ]; then
    OUT+="  [INFO] $APP_C is restart-looping; not waiting any longer"$'\n'; break
  fi
  sleep 10
done
printf '%s' "$OUT"
if [ "$FAILS" -eq 0 ]; then log "HEALTH: PASS"; exit 0; fi
log "HEALTH: FAIL ($FAILS check(s))"; exit 1
