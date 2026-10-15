#!/usr/bin/env bash
# Local, isolated end-to-end test of the Company BI deployment toolkit (tests A-E, plus the run-time half of F).
#
#   tests/local-e2e.sh --v1 <version> --v2 <version> [--dirty-ver <version>] [--keep] [--only A,B,C,D,E,F]
#
# Needs: Linux/WSL shell, docker + compose v2, python3 + cryptography, the two companybi images and
#        mysql:8.4.5 already present locally. v1/v2 must be real builds (v2 from a later commit).
#
# Isolation (hard guarantees, enforced below):
#   * compose project "cbitest", containers cbitest-app / cbitest-mysql, network cbitest-net,
#     port 127.0.0.1:30105, data under $E2E_WORK (default ~/cbitest); production names are never used;
#   * every docker object this script removes is removed BY EXPLICIT NAME;
#   * a before/after snapshot proves no other container/network/volume/image changed (test F);
#   * test-only images are derived from the real v2 image (labelled, manifest-edited, or entrypoint-broken)
#     and tagged companybi:<v2>-sim-*; their SCHEMA deltas are SIMULATED, not real migrations.
# Results: $E2E_WORK/e2e-results.tsv (PASS/FAIL per check) and $E2E_WORK/logs/*.log
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
V1=""; V2=""; DIRTYV=""; KEEP=no; ONLY="A,B,C,D,E,F"
while [ $# -gt 0 ]; do
  case "$1" in
    --v1) V1="${2:?}"; shift 2 ;;
    --v2) V2="${2:?}"; shift 2 ;;
    --dirty-ver) DIRTYV="${2:?}"; shift 2 ;;
    --keep) KEEP=yes; shift ;;
    --only) ONLY="${2:?}"; shift 2 ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done
[ -n "$V1" ] && [ -n "$V2" ] || { echo "--v1 and --v2 are required" >&2; exit 2; }

PROJECT=cbitest; APP=cbitest-app; DB=cbitest-mysql; NET=cbitest-net; PORT=30105
WORK="${E2E_WORK:-$HOME/cbitest}"; ENVF="$WORK/cbitest.env"; HOMEDIR="$WORK/home"; LOGS="$WORK/logs"
BASE="http://127.0.0.1:$PORT"; BOOT_PW="TestOnly-Boot-Pass-2026"
export COMPANY_BI_ENV_FILE="$ENVF" COMPANY_BI_HEALTH_WAIT="${COMPANY_BI_HEALTH_WAIT:-240}"
RES="$WORK/e2e-results.tsv"
SIM_TAGS=()

mkdir -p "$LOGS"; : > "$RES"
say() { printf '\n=== %s ===\n' "$*"; }
res() { # res <id> <PASS|FAIL> <description> [evidence]
  printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "${4:-}" >> "$RES"
  printf '  [%s] %s  %s%s\n' "$2" "$1" "$3" "${4:+  -- $4}"
}
ok_if() { # ok_if <id> <description> <evidence-on-fail> <command...>
  local id="$1" d="$2" ev="$3"; shift 3
  if "$@" >/dev/null 2>&1; then res "$id" PASS "$d"; else res "$id" FAIL "$d" "$ev"; fi
}
S() { local n="$1"; shift; "$@" > "$LOGS/$n.log" 2>&1; RC=$?; }   # run a script, log it, set RC
sc() { bash "$HERE/scripts/$1" "${@:2}"; }
lib() { ( . "$HERE/scripts/lib.sh"; load_env; "$@" ); }
sql() { docker exec -i "$DB" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -N -B -uroot' <<<"$1" 2>/dev/null; }
st() { sed -n "s/^$1=//p" "$HOMEDIR/releases/state.env" 2>/dev/null | tail -1; }
app_ver() { docker inspect -f '{{index .Config.Labels "com.companybi.version"}}' "$APP" 2>/dev/null; }
app_id() { docker inspect -f '{{.Id}}' "$APP" 2>/dev/null; }
app_running() { [ "$(docker inspect -f '{{.State.Running}}' "$APP" 2>/dev/null)" = true ]; }
login_ok() { python3 "$HERE/tests/smoke.py" login "$BASE" admin "$1" ${2:+--save "$2"} >/dev/null 2>&1; }
authed() { python3 "$HERE/tests/smoke.py" authed "$BASE" --token-file "$WORK/token" >/dev/null 2>&1; }
gzhas() { local n; n="$(zcat "$1" 2>/dev/null | grep -c -- "$2" || true)"; [ "${n:-0}" -gt 0 ]; }   # no `grep -q` after a pipe (pipefail/SIGPIPE)
DASH_NAME="e2e-dashboard"
dash_make() { python3 "$HERE/tests/smoke.py" dash-create "$BASE" --token-file "$WORK/token" --name "$DASH_NAME" | sed -n 's/^DASH CREATED id=//p' > "$WORK/dash.id"; [ -s "$WORK/dash.id" ]; }
dash_ok() { [ -s "$WORK/dash.id" ] && python3 "$HERE/tests/smoke.py" dash-get "$BASE" --token-file "$WORK/token" --id "$(cat "$WORK/dash.id")" --name "$DASH_NAME" >/dev/null 2>&1; }
healthy() { sc health-check.sh --wait "${1:-0}" >/dev/null 2>&1; }
snapshot() { # objects that are NOT part of this test
  { docker ps -a --format 'C {{.ID}} {{.Names}} {{.Image}} {{.Status}}' | grep -v -E " (${APP}|${DB}) " | sed -E 's/ (Up|Exited|Created)[^$]*$//' 
    docker network ls --format 'N {{.ID}} {{.Name}}' | grep -v " ${NET}\$"
    docker volume ls --format 'V {{.Name}}'
    docker images --format 'I {{.Repository}}:{{.Tag}} {{.ID}}' | grep -v -E '^I (companybi:|<none>)|^I mysql:8\.4\.5'; } | sort
}
want() { [[ ",$ONLY," == *",$1,"* ]]; }

# ---------------------------------------------------------------- derived (simulated) images
derive() { # derive <base_ver> <suffix> <mode>   -> sets DERIVED=<version>
  local base="$1" suf="$2" mode="$3" nv fp ofp tmp
  nv="${base}-sim-${suf}"
  ofp="$(docker run --rm --network none --entrypoint /bin/cat "companybi:$base" /opt/apps/release/SCHEMA | sed -n 's/^schema.fingerprint=//p')"
  fp="$(printf '%s' "$nv" | sha256sum | awk '{print $1}')"
  tmp="$(mktemp -d)"
  {
    echo "FROM companybi:$base"
    echo "LABEL com.companybi.version=\"$nv\""
    echo "RUN set -e; f=/opt/apps/release; sed -i \"s/^version=.*/version=$nv/\" \$f/RELEASE"
    case "$mode" in
      same|broken-same) ;;
      newfp|broken-newfp|compat|broken-compat|destructive-compat)
        echo "RUN set -e; f=/opt/apps/release; sed -i \"s/^schema.fingerprint=.*/schema.fingerprint=$fp/\" \$f/SCHEMA; sed -i \"s/^schema_fingerprint=.*/schema_fingerprint=$fp/\" \$f/RELEASE; sed -i '/^schema.fingerprint=/d' \$f/SCHEMA; echo 'flyway.file.V99.1__simulated_$suf.sql=$fp' >> \$f/SCHEMA; echo 'schema.fingerprint=$fp' >> \$f/SCHEMA"
        ;;
    esac
    case "$mode" in
      compat|broken-compat|destructive-compat) echo "RUN echo '$ofp  # SIMULATED declaration for the e2e test' >> /opt/apps/release/COMPAT" ;;
    esac
    case "$mode" in
      destructive-compat) echo "RUN sed -i '/^schema.fingerprint=/d' /opt/apps/release/SCHEMA; echo 'risk.flyway.V99.1__simulated_$suf.sql=destructive-keyword' >> /opt/apps/release/SCHEMA; echo 'schema.fingerprint=$fp' >> /opt/apps/release/SCHEMA" ;;
    esac
    case "$mode" in
      broken-*) echo "ENTRYPOINT [\"/bin/sh\",\"-c\",\"echo SIMULATED_BOOT_FAILURE; sleep 2; exit 1\"]" ;;
    esac
  } > "$tmp/Dockerfile"
  docker build -q -t "companybi:$nv" "$tmp" >/dev/null || { echo "derive failed for $nv" >&2; rm -rf "$tmp"; return 1; }
  rm -rf "$tmp"; SIM_TAGS+=("companybi:$nv"); DERIVED="$nv"
}

cleanup_stack() { # by explicit name only
  docker rm -f "$APP" >/dev/null 2>&1; docker rm -f "$DB" >/dev/null 2>&1; docker network rm "$NET" >/dev/null 2>&1
  if [ -d "$HOMEDIR" ]; then docker run --rm -v "$WORK:/t" --entrypoint /bin/sh mysql:8.4.5 -c 'rm -rf /t/home' >/dev/null 2>&1; fi
  return 0
}

# ---------------------------------------------------------------- setup
say "setup (isolated project '$PROJECT', port $PORT, data $HOMEDIR)"
[ "$(basename "$WORK")" != "" ] && case "$WORK" in "$HOME"/*|/tmp/*) ;; *) echo "refusing E2E_WORK=$WORK" >&2; exit 2 ;; esac
for c in companybi-app companybi-mysql; do docker inspect "$c" >/dev/null 2>&1 && { echo "a production-named container '$c' exists on this host; refusing to run the e2e test here" >&2; exit 2; }; done
docker image inspect "companybi:$V1" >/dev/null 2>&1 || { echo "image companybi:$V1 missing" >&2; exit 2; }
docker image inspect "companybi:$V2" >/dev/null 2>&1 || { echo "image companybi:$V2 missing" >&2; exit 2; }
docker image inspect mysql:8.4.5 >/dev/null 2>&1 || { echo "mysql:8.4.5 missing" >&2; exit 2; }
cleanup_stack
rm -f "$ENVF"
bash "$HERE/scripts/install.sh" --init-env >/dev/null
sed -i -e "s|^COMPANY_BI_PROJECT=.*|COMPANY_BI_PROJECT=$PROJECT|" -e "s|^COMPANY_BI_HOME=.*|COMPANY_BI_HOME=$HOMEDIR|" \
       -e "s|^COMPANY_BI_PORT=.*|COMPANY_BI_PORT=$PORT|" \
       -e "s|^COMPANY_BI_ORIGIN_LIST=.*|COMPANY_BI_ORIGIN_LIST=http://localhost:$PORT,http://127.0.0.1:$PORT|" \
       -e "s|^COMPANY_BI_BOOTSTRAP_PASSWORD=.*|COMPANY_BI_BOOTSTRAP_PASSWORD=$BOOT_PW|" "$ENVF"
SNAP_BEFORE="$(snapshot)"
printf '%s\n' "$SNAP_BEFORE" > "$LOGS/snapshot-before.txt"
echo "other objects on this docker engine (must stay untouched):"; printf '%s\n' "$SNAP_BEFORE" | sed 's/^/   /' | head -20

# ================================================================ T  transfer simulation (tower has no registry)
INSTALL_ARGS=()
if want A; then
  say "T. transfer: export -> delete local tag -> tamper check -> load from tar during install"
  TR="$WORK/transfer"; rm -rf "$TR"; mkdir -p "$TR"
  ID_BEFORE="$(docker image inspect -f '{{.Id}}' "companybi:$V1")"
  S T1-export bash "$HERE/scripts/export-image.sh" --version "$V1" --with-mysql --out "$TR"
  TAR1="$TR/companybi_${V1}_linux-amd64.tar"; MTAR="$(ls "$TR"/mysql_*_linux-amd64.tar 2>/dev/null | head -1)"; BUNDLE="$TR/companybi_deploy_${V1}.tar.gz"
  if [ "$RC" = 0 ] && [ -s "$TAR1" ] && [ -s "$MTAR" ] && [ -s "$BUNDLE" ] && ( cd "$TR" && sha256sum -c ./*.sha256 >/dev/null 2>&1 ); then
    res T.1 PASS "export-image.sh produced image tar, mysql tar, deploy bundle, release record, all with valid .sha256" "$(du -h "$TAR1" | cut -f1) image tar"
  else res T.1 FAIL "export-image.sh artifacts" "rc=$RC see logs/T1-export.log"; fi
  grep -q "^image_id=$ID_BEFORE" "$TR/release_${V1}.txt" 2>/dev/null && res T.2 PASS "release record carries image_id, artifact sha256, git sha" "$(grep -E '^(git_sha|git_dirty|artifact_tar_sha256)=' "$TR/release_${V1}.txt" | tr '\n' ' ' | cut -c1-200)" || res T.2 FAIL "release record"
  # capture the listing first: `tar | grep -q` under pipefail can fail intermittently (SIGPIPE)
  BLIST="$(tar -tzf "$BUNDLE" 2>/dev/null || true)"
  BHAS="$(grep -c 'companybi/scripts/install.sh' <<<"$BLIST" || true)"
  BBAD="$(grep -c -E '(^|/)\.env$|\.sql\.gz$|\.tar$' <<<"$BLIST" || true)"
  [ "${BHAS:-0}" -ge 1 ] && [ "${BBAD:-0}" = 0 ] && res T.3 PASS "deploy bundle has the scripts and no .env / backups / image tars" || res T.3 FAIL "bundle content" "has_install=$BHAS offenders=$BBAD"
  mkdir -p "$TR/extract" && tar -xzf "$BUNDLE" -C "$TR/extract" && [ -x "$TR/extract/companybi/scripts/install.sh" ] && bash "$TR/extract/companybi/scripts/install.sh" --help >/dev/null 2>&1 && res T.4 PASS "bundle extracts with executable scripts (usable without Git)" || res T.4 FAIL "bundle usable"
  cp "$TAR1" "$TR/tampered.tar"; printf 'x' >> "$TR/tampered.tar"; cp "$TAR1.sha256" "$TR/tampered.tar.sha256"
  docker rmi "companybi:$V1" >/dev/null 2>&1       # exact tag: simulate a host that does not have the image yet
  S T5 sc install.sh --version "$V1" --image-tar "$TR/tampered.tar"
  [ "$RC" != 0 ] && grep -q 'SHA256 mismatch' "$LOGS/T5.log" && ! docker image inspect "companybi:$V1" >/dev/null 2>&1 && res T.5 PASS "tampered tar rejected before docker load (SHA256 mismatch), nothing installed" || res T.5 FAIL "tamper detection" "rc=$RC"
  INSTALL_ARGS=(--image-tar "$TAR1" --mysql-image-tar "$MTAR")
fi

# ================================================================ A  first install
if want A; then
  say "A. first install on an empty metadata database ($V1)"
  T0=$(date +%s); S A1-install sc install.sh --version "$V1" ${INSTALL_ARGS[@]+"${INSTALL_ARGS[@]}"}; A_SECS=$(( $(date +%s) - T0 ))
  [ "$(docker image inspect -f '{{.Id}}' "companybi:$V1" 2>/dev/null)" = "$ID_BEFORE" ] && res A.0 PASS "image ID identical after docker save -> docker load" "$ID_BEFORE" || res A.0 FAIL "image ID stable across save/load" "before=$ID_BEFORE after=$(docker image inspect -f '{{.Id}}' "companybi:$V1" 2>/dev/null)"
  [ "$RC" = 0 ] && res A.1 PASS "install.sh exit 0" "${A_SECS}s" || res A.1 FAIL "install.sh exit 0" "rc=$RC see logs/A1-install.log"
  ok_if A.2 "health-check passes" "see health-check output" healthy 0
  [ "$(st CURRENT_VERSION)" = "$V1" ] && res A.3 PASS "state records current version" "$V1" || res A.3 FAIL "state records current version" "$(st CURRENT_VERSION)"
  [ "$(app_ver)" = "$V1" ] && res A.4 PASS "running container is $V1 (label)" || res A.4 FAIL "running container label" "$(app_ver)"
  ok_if A.5 "app published on 127.0.0.1:$PORT only" "$(docker port "$APP" 2>&1)" bash -c "docker port $APP | grep -q '127.0.0.1:$PORT' && ! docker port $APP | grep -q '0.0.0.0'"
  ok_if A.6 "MySQL publishes no host port" "$(docker port "$DB" 2>&1)" bash -c "[ -z \"\$(docker port $DB)\" ]"
  ok_if A.7 "only cbitest-app / cbitest-mysql / cbitest-net were created" "unexpected object" bash -c "[ \"\$(docker ps -a --format '{{.Names}}' | grep -c '^cbitest-')\" = 2 ] && docker network inspect $NET >/dev/null"
  NT="$(sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='companybi_meta'")"
  NMIG="$(docker run --rm --network none --entrypoint /bin/cat "companybi:$V1" /opt/apps/release/SCHEMA | grep -c '^flyway\.file\.')"
  NF="$(sql "SELECT COUNT(*) FROM companybi_meta.de_standalone_version WHERE success=1")"
  NCO="$(sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='companybi_meta' AND table_name LIKE 'company\\_%'")"
  NCO_WANT="$(cat "$HERE"/../../core/core-backend/src/main/resources/db/company/*.sql | grep -o -E 'CREATE TABLE IF NOT EXISTS company_[a-z_]+' | sort -u | wc -l)"
  NCORE="$(sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='companybi_meta' AND table_name IN ('core_datasource','core_dataset_group','data_visualization_info','core_sys_setting')")"
  if [ "${NF:-0}" -ge "${NMIG:-1}" ] && [ "${NCO:-0}" -ge "${NCO_WANT:-1}" ] && [ "${NCORE:-0}" = 4 ]; then
    res A.8 PASS "metadata DB initialised from empty (Flyway + company schema)" "flyway success rows=$NF >= migrations=$NMIG; company tables=$NCO >= expected $NCO_WANT; core tables=4/4; total tables=$NT"
  else res A.8 FAIL "metadata DB initialised" "flyway_ok=$NF/$NMIG company=$NCO/$NCO_WANT core=$NCORE/4"; fi
  [ "$(sql "SELECT COUNT(*) FROM companybi_meta.company_bi_user WHERE id=1")" = 1 ] && res A.9 PASS "bootstrap admin created (id=1)" || res A.9 FAIL "bootstrap admin created"
  ok_if A.10 "login with the bootstrap password works" "see smoke.py" login_ok "$BOOT_PW" "$WORK/token"
  ok_if A.11 "saved token opens a protected API" "see smoke.py" authed
  ok_if A.12 "protected API rejects anonymous calls" "accepted?" python3 "$HERE/tests/smoke.py" anon "$BASE"
  login_ok "definitely-wrong-password" && res A.13 FAIL "wrong password rejected" || res A.13 PASS "wrong password rejected"
  ok_if A.14 "no datasource secret of the old remote DB reachable in container env/config" "found" bash -c "! docker exec $APP sh -c 'env; cat /proc/1/cmdline' | tr '\\0' ' ' | grep -q -E 'Password123|39\\.101\\.192\\.183'"
  FE="$(docker exec "$APP" printenv COMPANY_FEISHU_DRY_RUN 2>/dev/null)|$(docker exec "$APP" printenv COMPANY_FEISHU_INTERNAL_TOKEN 2>/dev/null)|$(docker exec "$APP" printenv COMPANY_FEISHU_SHOT_BASE_URL 2>/dev/null)"
  [ "$FE" = "true||http://127.0.0.1:9" ] && res A.14b PASS "Feishu is forced to dry-run in the running container; no token; screenshot service points at a dead port" "$FE" || res A.14b FAIL "Feishu safety env" "$FE"
  [ "$(sql "SELECT COUNT(*) FROM companybi_meta.company_feishu_subscription")" = 0 ] && [ "$(sql "SELECT COUNT(*) FROM companybi_meta.company_feishu_app_config")" = 0 ] && res A.14c PASS "no Feishu app config and no subscriptions exist after a clean install (nothing can be delivered)" || res A.14c FAIL "Feishu tables empty"
  MYC="$(sql "SELECT CONCAT_WS('|', @@innodb_buffer_pool_size, @@transaction_isolation, @@time_zone, @@max_allowed_packet)")"
  [ "$MYC" = "268435456|READ-COMMITTED|+08:00|67108864" ] && res A.14d PASS "mysql/my.cnf is really applied in the running MySQL (buffer pool 256M, READ-COMMITTED, +08:00, 64M packets)" "$MYC" || res A.14d FAIL "my.cnf applied" "$MYC"
  [ "$(stat -c %a "$HOMEDIR/mysql/conf/companybi.cnf" 2>/dev/null)" = 644 ] && res A.14e PASS "mounted mysql config is the private mode-644 copy under COMPANY_BI_HOME" || res A.14e FAIL "mysql conf copy mode"
  dash_make && res A.18 PASS "a dashboard was created through the real API (POST /de2api/dataVisualization/saveCanvas, admin token)" "id=$(cat "$WORK/dash.id")" || res A.18 FAIL "dashboard created via API"
  dash_ok && res A.19 PASS "that dashboard can be read back through the API (findById) and its name matches" || res A.19 FAIL "dashboard read back"
  [ "$(sql "SELECT COUNT(*) FROM companybi_meta.data_visualization_info WHERE name='$DASH_NAME'")" -ge 1 ] && res A.20 PASS "the dashboard row is in the dedicated metadata DB (companybi_meta.data_visualization_info)" || res A.20 FAIL "dashboard row in companybi_meta"
  sleep 45
  echo "idle memory: $(docker stats --no-stream --format '{{.Name}} {{.MemUsage}} cpu={{.CPUPerc}}' "$APP" "$DB" | tr '\n' ';')" | tee "$LOGS/idle-memory.txt"
  res A.15 PASS "idle memory sampled (not a pass/fail criterion)" "$(tr '\n' ' ' < "$LOGS/idle-memory.txt")"
  S A2-reinstall sc install.sh --version "$V1"; ID_NOW="$(app_id)"
  [ "$RC" != 0 ] && res A.16 PASS "second install.sh over a live install is refused" "rc=$RC: $(grep -m1 -E 'ERROR' "$LOGS/A2-reinstall.log" | cut -c1-140)" || res A.16 FAIL "second install refused" "rc=0"
  [ "$ID_NOW" = "$(app_id)" ] && res A.17 PASS "refused install left containers untouched" || res A.17 FAIL "containers untouched"
fi

# ================================================================ B  persistence
if want B; then
  say "B. persistence across restart and container re-creation"
  sql "CREATE TABLE IF NOT EXISTS companybi_meta.e2e_marker (k VARCHAR(40) PRIMARY KEY, v VARCHAR(100)); REPLACE INTO companybi_meta.e2e_marker VALUES ('m1','written-before-restart');" >/dev/null
  docker exec "$APP" sh -c 'echo persisted > /opt/dataease2.0/data/e2e_marker.txt'
  login_ok "$BOOT_PW" "$WORK/token"
  [ -s "$WORK/dash.id" ] || dash_make || true     # B run on its own: create the dashboard here
  # sample memory every 3 s for ~2 min while the app starts again: the START-UP peak matters for the host budget
  ( for _ in $(seq 1 40); do docker stats --no-stream --format '{{.Name}} {{.MemUsage}}' "$APP" "$DB" 2>/dev/null; sleep 3; done > "$LOGS/startup-mem.txt" ) &
  SAMPLER=$!
  docker restart "$APP" "$DB" >/dev/null
  healthy 240 && res B.1 PASS "stack healthy after docker restart (both containers)" || res B.1 FAIL "healthy after restart"
  wait "$SAMPLER" 2>/dev/null
  PK="$(awk '{v=$2; u=v; gsub(/[0-9.]/,"",u); n=v; gsub(/[A-Za-z]/,"",n); m=n+0; if(u=="GiB")m=n*1024; if(m>mx[$1])mx[$1]=m} END{for(k in mx)printf "%s peak=%dMiB ",k,mx[k]}' "$LOGS/startup-mem.txt")"
  res B.1b PASS "start-up memory peak sampled (informational, not a pass/fail criterion)" "$PK"
  [ "$(sql "SELECT v FROM companybi_meta.e2e_marker WHERE k='m1'")" = "written-before-restart" ] && res B.2 PASS "DB marker row survived restart" || res B.2 FAIL "DB marker row survived restart"
  dash_ok && res B.2b PASS "dashboard created before the restart is still readable through the API" || res B.2b FAIL "dashboard after restart"
  authed && res B.3 PASS "token issued BEFORE the restart is still valid (token secret stable)" || res B.3 FAIL "old token valid after restart"
  # change the bootstrap password in .env: must have no effect because admin id=1 exists
  sed -i "s|^COMPANY_BI_BOOTSTRAP_PASSWORD=.*|COMPANY_BI_BOOTSTRAP_PASSWORD=Changed-Later-Pass-2026|" "$ENVF"
  docker rm -f "$APP" "$DB" >/dev/null 2>&1       # explicit names: simulate full container re-creation
  lib compose up -d mysql >"$LOGS/B4-recreate.log" 2>&1
  sleep 20; lib compose up -d app >>"$LOGS/B4-recreate.log" 2>&1
  healthy 300 && res B.4 PASS "stack healthy after both containers were removed and re-created from compose" || res B.4 FAIL "healthy after re-creation"
  [ "$(sql "SELECT v FROM companybi_meta.e2e_marker WHERE k='m1'")" = "written-before-restart" ] && res B.5 PASS "DB marker row survived container re-creation (bind-mounted data)" || res B.5 FAIL "DB marker survived re-creation"
  [ "$(docker exec "$APP" cat /opt/dataease2.0/data/e2e_marker.txt 2>/dev/null)" = persisted ] && res B.6 PASS "file in app/data survived re-creation" || res B.6 FAIL "app/data file survived"
  dash_ok && res B.6b PASS "dashboard still readable after BOTH containers were removed and re-created" || res B.6b FAIL "dashboard after re-creation"
  authed && res B.7 PASS "old token still valid after re-creation" || res B.7 FAIL "old token valid after re-creation"
  login_ok "$BOOT_PW" && res B.8 PASS "original admin password still works (bootstrap password change ignored once admin exists)" || res B.8 FAIL "original admin password works"
  login_ok "Changed-Later-Pass-2026" && res B.9 FAIL "changed bootstrap password must NOT take effect" || res B.9 PASS "changed bootstrap password did not overwrite the admin"
  SR="$(ls "$HOMEDIR/app/data/static-resource" 2>/dev/null | wc -l)"; MO="$(find "$HOMEDIR/app/data/map-origin" -type f 2>/dev/null | wc -l)"
  [ "$MO" -gt 0 ] && res B.10 PASS "image seed resources present under the mounted data dir" "static-resource entries=$SR map files=$MO" || res B.10 FAIL "seed resources present" "map files=$MO"
  ok_if B.11 "app/conf persisted share-secret.json on the host" "missing" test -s "$HOMEDIR/app/conf/share-secret.json"
fi

# ================================================================ C  version update
if want C; then
  say "C. upgrade $V1 -> $V2"
  [ "$(app_ver)" = "$V1" ] || { echo "precondition: running version is $(app_ver), expected $V1" >&2; }
  login_ok "$BOOT_PW" "$WORK/token"; ID0="$(app_id)"
  S C1-dry sc upgrade.sh --version "$V2" --dry-run
  [ "$RC" = 0 ] && [ "$(app_id)" = "$ID0" ] && res C.1 PASS "--dry-run prints the assessment and changes nothing" "$(grep -m1 'schema  :' "$LOGS/C1-dry.log" | cut -c1-100)" || res C.1 FAIL "--dry-run changes nothing" "rc=$RC"
  T0=$(date +%s); S C2-upgrade sc upgrade.sh --version "$V2" --yes; C_SECS=$(( $(date +%s) - T0 ))
  [ "$RC" = 0 ] && res C.2 PASS "upgrade.sh exit 0" "${C_SECS}s total (stop old -> healthy new, incl. backup)" || res C.2 FAIL "upgrade.sh exit 0" "rc=$RC see logs/C2-upgrade.log"
  [ "$(app_ver)" = "$V2" ] && res C.3 PASS "running container is $V2" || res C.3 FAIL "running version" "$(app_ver)"
  [ "$(st CURRENT_VERSION)/$(st PREVIOUS_GOOD_VERSION)/$(st ROLLBACK_MODE)" = "$V2/$V1/auto" ] && res C.4 PASS "state: current=$V2 previous=$V1 rollback_mode=auto" || res C.4 FAIL "state after upgrade" "$(st CURRENT_VERSION)/$(st PREVIOUS_GOOD_VERSION)/$(st ROLLBACK_MODE)"
  BK="$(st LAST_BACKUP)"
  [ -s "$BK" ] && gzip -t "$BK" 2>/dev/null && gzhas "$BK" 'e2e_marker' && res C.5 PASS "pre-upgrade backup exists, valid gzip, contains the marker table" "$(basename "$BK") $(stat -c %s "$BK")B" || res C.5 FAIL "pre-upgrade backup valid" "$BK"
  [ "$(stat -c %a "$BK" 2>/dev/null)" = 600 ] && res C.6 PASS "backup file mode 600" || res C.6 FAIL "backup mode" "$(stat -c %a "$BK" 2>/dev/null)"
  docker image inspect "companybi:$V1" >/dev/null 2>&1 && res C.7 PASS "old image $V1 kept (not deleted by upgrade)" || res C.7 FAIL "old image kept"
  dash_ok && res C.8b PASS "dashboard still readable after the version upgrade $V1 -> $V2" || res C.8b FAIL "dashboard after upgrade"
  authed && res C.8 PASS "token from before the upgrade still valid" || res C.8 FAIL "token valid after upgrade"
  [ "$(sql "SELECT v FROM companybi_meta.e2e_marker WHERE k='m1'")" = "written-before-restart" ] && res C.9 PASS "data intact after upgrade" || res C.9 FAIL "data intact"
  grep -q -P "upgrade\t$V1\t$V2\tOK" "$HOMEDIR/releases/history.tsv" 2>/dev/null && res C.10 PASS "history.tsv records the upgrade" || res C.10 FAIL "history recorded"
  S C3-same sc upgrade.sh --version "$V2" --yes
  [ "$RC" != 0 ] && res C.11 PASS "upgrading to the already-running version is refused" || res C.11 FAIL "same-version upgrade refused"
fi

# ================================================================ D  failed upgrade, schema unchanged
if want D; then
  say "D. failed upgrade with unchanged schema -> automatic image rollback"
  CUR="$(st CURRENT_VERSION)"; CUR="${CUR:-$V2}"
  derive "$CUR" d-bad broken-same; DV="$DERIVED"; login_ok "$BOOT_PW" "$WORK/token"
  T0=$(date +%s); S D1-badupgrade sc upgrade.sh --version "$DV" --yes; D_SECS=$(( $(date +%s) - T0 ))
  [ "$RC" != 0 ] && res D.1 PASS "upgrade to a broken image exits non-zero" "${D_SECS}s" || res D.1 FAIL "broken upgrade exit non-zero"
  [ "$(app_ver)" = "$CUR" ] && app_running && res D.2 PASS "app rolled back automatically to $CUR and is running" || res D.2 FAIL "automatic rollback" "now=$(app_ver)"
  healthy 120 && res D.3 PASS "rolled-back version passes the health check" || res D.3 FAIL "healthy after rollback"
  [ "$(st LAST_OP_RESULT)" = FAILED_ROLLED_BACK ] && [ "$(st PENDING_MANUAL)" = no ] && res D.4 PASS "state: FAILED_ROLLED_BACK, no pending manual action" || res D.4 FAIL "state" "$(st LAST_OP_RESULT)/$(st PENDING_MANUAL)"
  [ "$(sql "SELECT v FROM companybi_meta.e2e_marker WHERE k='m1'")" = "written-before-restart" ] && res D.5 PASS "data intact after failed upgrade + rollback" || res D.5 FAIL "data intact"
  dash_ok && res D.5b PASS "dashboard still readable after the failed upgrade + automatic rollback" || res D.5b FAIL "dashboard after rollback"
  authed && res D.6 PASS "old token valid after failed upgrade + rollback" || res D.6 FAIL "token valid"
  [ -s "$(st LAST_BACKUP)" ] && res D.7 PASS "the pre-upgrade backup was kept" || res D.7 FAIL "backup kept"
  grep -q "SIMULATED_BOOT_FAILURE\|app| " "$LOGS/D1-badupgrade.log" && res D.8 PASS "failure output shows the new version's log tail" || res D.8 PASS "(log tail not captured; informational)"
fi

# ================================================================ E  schema-change risk
if want E; then
  say "E. schema-change detection and unsafe-rollback prevention"
  CUR="$(st CURRENT_VERSION)"; CUR="${CUR:-$V2}"
  derive "$CUR" e-newfp newfp; V4="$DERIVED"
  ID0="$(app_id)"
  S Ea sc upgrade.sh --version "$V4" --yes
  [ "$RC" != 0 ] && [ "$(app_ver)" = "$CUR" ] && [ "$(app_id)" = "$ID0" ] && res E.1 PASS "schema changed + compatibility unproven: upgrade refused without --accept-schema-risk, nothing touched" "$(grep -m1 'class=' "$LOGS/Ea.log" | cut -c1-90)" || res E.1 FAIL "refused without flag" "rc=$RC ver=$(app_ver)"
  grep -q 'class=UNCONFIRMED' "$LOGS/Ea.log" && res E.2 PASS "classified UNCONFIRMED with the simulated delta listed" || res E.2 FAIL "classified UNCONFIRMED"
  S Eb sc upgrade.sh --version "$V4" --yes --accept-schema-risk
  [ "$RC" = 0 ] && [ "$(app_ver)" = "$V4" ] && [ "$(st ROLLBACK_MODE)" = manual ] && res E.3 PASS "with --accept-schema-risk the upgrade runs; rollback_mode is recorded as manual" || res E.3 FAIL "accepted risky upgrade" "rc=$RC mode=$(st ROLLBACK_MODE)"
  ID1="$(app_id)"; S Ec sc rollback.sh --to "$CUR" --yes
  [ "$RC" = 3 ] && [ "$(app_ver)" = "$V4" ] && [ "$(app_id)" = "$ID1" ] && res E.4 PASS "image-only rollback REFUSED (exit 3) because schema compat is unproven; nothing changed" || res E.4 FAIL "unsafe rollback refused" "rc=$RC ver=$(app_ver)"
  [ "$(sql "SELECT v FROM companybi_meta.e2e_marker WHERE k='m1'")" = "written-before-restart" ] && res E.5 PASS "DB untouched by the refused rollback" || res E.5 FAIL "DB untouched"
  derive "$V4" e-bad broken-newfp; V5="$DERIVED"
  S Ed sc upgrade.sh --version "$V5" --yes --accept-schema-risk
  BK="$(st LAST_BACKUP)"
  [ "$RC" != 0 ] && ! app_running && res E.6 PASS "failed upgrade with unproven schema: NO automatic rollback; new app stopped" || res E.6 FAIL "no auto rollback" "rc=$RC running=$(app_running && echo y || echo n)"
  [ "$(docker inspect -f '{{.State.Running}}' "$DB")" = true ] && res E.7 PASS "MySQL kept running and untouched" || res E.7 FAIL "MySQL running"
  [ "$(st PENDING_MANUAL)" = yes ] && [ "$(st LAST_OP_RESULT)" = FAILED_MANUAL_ROLLBACK_REQUIRED ] && res E.8 PASS "state: PENDING_MANUAL=yes, FAILED_MANUAL_ROLLBACK_REQUIRED" || res E.8 FAIL "pending state" "$(st PENDING_MANUAL)/$(st LAST_OP_RESULT)"
  [ -s "$BK" ] && res E.9 PASS "backup retained for manual recovery" "$(basename "$BK")" || res E.9 FAIL "backup retained"
  grep -q 'docs/rollback\|rollback.sh --to' "$LOGS/Ed.log" && res E.10 PASS "manual recovery steps printed (no silent failure)" || res E.10 FAIL "manual steps printed"
  S Ee sc upgrade.sh --version "$V2" --yes --accept-schema-risk
  [ "$RC" != 0 ] && grep -q PENDING_MANUAL "$LOGS/Ee.log" && res E.11 PASS "further upgrades blocked while PENDING_MANUAL=yes" || res E.11 FAIL "blocked while pending"
  # simulate writes made by the failed new version AFTER the backup, then do the explicit DB restore
  sql "REPLACE INTO companybi_meta.e2e_marker VALUES ('m2','written-after-backup');" >/dev/null
  S Ef sc rollback.sh --to "$V4" --restore-db "$BK" --yes
  [ "$RC" = 0 ] && [ "$(app_ver)" = "$V4" ] && app_running && res E.12 PASS "explicit rollback with --restore-db succeeds and $V4 runs" || res E.12 FAIL "restore rollback" "rc=$RC ver=$(app_ver)"
  [ -z "$(sql "SELECT v FROM companybi_meta.e2e_marker WHERE k='m2'")" ] && [ "$(sql "SELECT v FROM companybi_meta.e2e_marker WHERE k='m1'")" = "written-before-restart" ] && res E.13 PASS "DB restored to the backup state (marker m2 gone, m1 kept)" || res E.13 FAIL "DB restored"
  SAFE="$(ls -1t "$HOMEDIR"/backups/*pre-rollback-from-* 2>/dev/null | head -1)"
  [ -n "$SAFE" ] && gzhas "$SAFE" 'written-after-backup' && res E.14 PASS "safety dump taken BEFORE the restore still holds the post-backup data" "$(basename "$SAFE")" || res E.14 FAIL "safety dump holds m2"
  [ "$(st PENDING_MANUAL)" = no ] && healthy 60 && res E.15 PASS "after recovery: pending cleared, health PASS" || res E.15 FAIL "pending cleared"
  derive "$V4" e-comp broken-compat; V6="$DERIVED"; ID2="$(app_id)"
  S Eg sc upgrade.sh --version "$V6" --yes
  [ "$RC" != 0 ] && [ "$(app_ver)" = "$V4" ] && app_running && res E.16 PASS "declared-compatible schema + failure -> automatic image rollback allowed and performed" "$(grep -m1 'class=' "$LOGS/Eg.log" | cut -c1-100)" || res E.16 FAIL "declared-compat auto rollback" "rc=$RC ver=$(app_ver)"
  derive "$V4" e-destr destructive-compat; V7="$DERIVED"
  S Eh1 sc upgrade.sh --version "$V7" --dry-run
  [ "$RC" != 0 ] && grep -q 'destructive_keywords=yes' "$LOGS/Eh1.log" && res E.17 PASS "declared-compatible but destructive keyword: refused without --accept-schema-risk" || res E.17 FAIL "destructive flagged" "rc=$RC"
  S Eh2 sc upgrade.sh --version "$V7" --dry-run --accept-schema-risk
  [ "$RC" = 0 ] && grep -q 'rollback_mode_if_upgraded=manual' "$LOGS/Eh2.log" && res E.18 PASS "with the flag, dry-run reports rollback mode manual" || res E.18 FAIL "dry-run manual" "rc=$RC"
  if [ -n "$DIRTYV" ] && docker image inspect "companybi:$DIRTYV" >/dev/null 2>&1; then
    S Ei sc upgrade.sh --version "$DIRTYV" --dry-run --accept-schema-risk
    [ "$RC" != 0 ] && grep -q DIRTY "$LOGS/Ei.log" && res E.19 PASS "image built from a dirty tree is refused" || res E.19 FAIL "dirty refused" "rc=$RC"
  else res E.19 FAIL "dirty-image check" "NOT RUN (no --dirty-ver image)"; fi
fi

# ================================================================ F  scope (run-time half)
if want F; then
  say "F. anti-misoperation scope"
  SNAP_AFTER="$(snapshot)"; printf '%s\n' "$SNAP_AFTER" > "$LOGS/snapshot-after.txt"
  if [ "$SNAP_BEFORE" = "$SNAP_AFTER" ]; then res F.1 PASS "no other container/network/volume/image changed during the whole run" "$(printf '%s\n' "$SNAP_AFTER" | wc -l) foreign objects compared"
  else res F.1 FAIL "foreign objects changed" "$(diff <(printf '%s\n' "$SNAP_BEFORE") <(printf '%s\n' "$SNAP_AFTER") | head -5 | tr '\n' ' ')"; fi
  S F2 bash "$HERE/tests/static-audit.sh"
  [ "$RC" = 0 ] && res F.2 PASS "static audit of scripts + compose passes" || res F.2 FAIL "static audit" "see logs/F2.log"
  S F3 sc upgrade.sh --version latest --dry-run
  [ "$RC" != 0 ] && res F.3 PASS "'latest' is refused as a version" || res F.3 FAIL "'latest' refused"
  ( cd "$HERE" && COMPANY_BI_ENV_FILE=/nonexistent bash scripts/status.sh >/dev/null 2>&1 ); [ $? != 0 ] && res F.4 PASS "scripts refuse to run without an env file (no implicit defaults against the wrong stack)" || res F.4 FAIL "no env file refused"
fi

# ---------------------------------------------------------------- wrap up
if [ "$KEEP" = no ]; then
  say "cleanup (explicit names only)"
  cleanup_stack
  for t in "${SIM_TAGS[@]:-}"; do [ -n "$t" ] && docker rmi "$t" >/dev/null 2>&1; done
  echo "removed: $APP $DB $NET $HOMEDIR ${SIM_TAGS[*]:-}"
fi
say "summary"
P=$(grep -c -P '\tPASS\t' "$RES"); F=$(grep -c -P '\tFAIL\t' "$RES")
echo "PASS=$P FAIL=$F  (details: $RES)"
grep -P '\tFAIL\t' "$RES" | sed 's/^/  FAILED: /'
[ "$F" = 0 ]
