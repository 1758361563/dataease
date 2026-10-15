#!/usr/bin/env bash
# Verify the FORMAL release artifacts (the files you would scp to a server), not locally built images.
#
#   tests/release-verify.sh --dist <dir> --version <v> --expect-sha <full git sha> [--stage S,R] [--keep] [--reuse]
#
#   <dir> holds what export-image.sh produced:
#         companybi_<v>_linux-amd64.tar(+.sha256)  companybi_deploy_<v>.tar.gz(+.sha256)
#         mysql_8.4.5_linux-amd64.tar(+.sha256)    release_<v>.txt
#
# Stage S (release smoke): checksum -> delete the local image tag -> install FROM THE TAR with the scripts that
#         are inside the deploy bundle -> empty-DB init -> admin login -> company APIs -> dashboard create/read ->
#         restart persistence -> health check / version identification -> RELEASE vs real commit.
# Stage R (real DB restore, same stack): create a user + permission + dashboards -> backup-db.sh -> controlled
#         damage -> rollback.sh --restore-db -> prove the data is back (table checksums + API) -> then do it
#         again with the SCRIPT-LESS procedure from docs/rollback.md section 2.7.
#
# Isolation: compose project "cbirel", containers cbirel-app/cbirel-mysql, network cbirel-net, port
# 127.0.0.1:30106, data under $RV_WORK (default ~/cbirel). Never touches production names, a real database,
# Guanyuan, or any object it did not create; everything it removes is removed BY EXPLICIT NAME. A before/after
# snapshot of all other docker objects is compared at the end.
# Results: $RV_WORK/release-verify-results.tsv, logs in $RV_WORK/logs/.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST=""; V=""; EXPECT_SHA=""; STAGES="S,R"; KEEP=no; REUSE=no
while [ $# -gt 0 ]; do
  case "$1" in
    --dist) DIST="${2:?}"; shift 2 ;;
    --version) V="${2:?}"; shift 2 ;;
    --expect-sha) EXPECT_SHA="${2:?}"; shift 2 ;;
    --stage) STAGES="${2:?}"; shift 2 ;;
    --keep) KEEP=yes; shift ;;
    --reuse) REUSE=yes; KEEP=yes; shift ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done
[ -n "$DIST" ] && [ -n "$V" ] && [ -n "$EXPECT_SHA" ] || { echo "--dist, --version and --expect-sha are required" >&2; exit 2; }
[ -d "$DIST" ] || { echo "dist dir not found: $DIST" >&2; exit 2; }

PROJECT=cbirel; APP=cbirel-app; DB=cbirel-mysql; NET=cbirel-net; PORT=30106
WORK="${RV_WORK:-$HOME/cbirel}"; LOGS="$WORK/logs"; HOMEDIR="$WORK/home"; BD="$WORK/bundle/companybi"; ENVF="$BD/.env"
BASE="http://127.0.0.1:$PORT"; BOOT_PW="TestOnly-Boot-Pass-2026"; DBNAME=companybi_meta
IMG="companybi:$V"; SMOKE="$HERE/tests/smoke.py"
RES="$WORK/release-verify-results.tsv"
TAR="$DIST/companybi_${V}_linux-amd64.tar"; BUNDLE="$DIST/companybi_deploy_${V}.tar.gz"; MTAR="$DIST/mysql_8.4.5_linux-amd64.tar"; REC="$DIST/release_${V}.txt"
case "$WORK" in "$HOME"/*|/tmp/*) ;; *) echo "refusing RV_WORK=$WORK" >&2; exit 2 ;; esac
mkdir -p "$LOGS"; [ "$REUSE" = yes ] || : > "$RES"

say() { printf '\n=== %s ===\n' "$*"; }
res() { printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "${4:-}" >> "$RES"; printf '  [%s] %s  %s%s\n' "$2" "$1" "$3" "${4:+  -- $4}"; }
ok_if() { local id="$1" d="$2" ev="$3"; shift 3; if "$@" >/dev/null 2>&1; then res "$id" PASS "$d"; else res "$id" FAIL "$d" "$ev"; fi; }
want() { [[ ",$STAGES," == *",$1,"* ]]; }
S() { local n="$1"; shift; "$@" > "$LOGS/$n.log" 2>&1; RC=$?; }
bd() { bash "$BD/scripts/$1" "${@:2}"; }                       # scripts FROM THE EXTRACTED DEPLOY BUNDLE
sql() { docker exec -i "$DB" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -N -B -uroot' <<<"$1" 2>/dev/null; }
app_id() { docker inspect -f '{{.Id}}' "$APP" 2>/dev/null; }
gzhas() { local n; n="$(zcat "$1" 2>/dev/null | grep -c -- "$2" || true)"; [ "${n:-0}" -gt 0 ]; }
healthy() { bd health-check.sh --wait "${1:-0}" >/dev/null 2>&1; }
st() { sed -n "s/^$1=//p" "$HOMEDIR/releases/state.env" 2>/dev/null | tail -1; }

# JSON helper: jx '<python expr on d>'  (reads JSON on stdin; unwraps DataEase {"code":0,"data":...} envelopes)
jx() { python3 -c '
import sys, json
raw = sys.stdin.read()
try:
    d = json.loads(raw)
except Exception:
    print("")
    sys.exit(0)
if isinstance(d, dict) and "code" in d and "data" in d:
    d = d["data"]
try:
    r = eval(sys.argv[1])
except Exception:
    r = ""
print(r if r is not None else "")
' "$1"; }
# api <METHOD> <path> [json-body] [token-file]  -> sets APISTAT, APIBODY
api() {
  local out; out="$(python3 "$SMOKE" call "$BASE" --method "$1" --path "$2" --token-file "${4:-$WORK/tok_admin}" ${3:+--body "$3"} 2>/dev/null)"
  APISTAT="$(head -n 1 <<<"$out" | awk '{print $2}')"; APIBODY="$(tail -n +2 <<<"$out")"
}
login_as() { python3 "$SMOKE" login "$BASE" "$1" "$2" ${3:+--save "$3"} >/dev/null 2>&1; }
dash_make() { # dash_make <name> -> prints id
  python3 "$SMOKE" dash-create "$BASE" --token-file "$WORK/tok_admin" --name "$1" 2>/dev/null | sed -n 's/^DASH CREATED id=//p'; }
dash_see() { # dash_see <id> <name> [token-file]
  python3 "$SMOKE" dash-get "$BASE" --token-file "${3:-$WORK/tok_admin}" --id "$1" --name "$2" >/dev/null 2>&1; }
snapshot() { # docker objects that are NOT ours
  { docker ps -a --format 'C {{.ID}} {{.Names}} {{.Image}}' | grep -v -E " (${APP}|${DB}) " | sed -E 's/ (Up|Exited|Created)[^$]*$//'
    docker network ls --format 'N {{.ID}} {{.Name}}' | grep -v " ${NET}\$"
    docker volume ls --format 'V {{.Name}}'
    docker images --format 'I {{.Repository}}:{{.Tag}} {{.ID}}' | grep -v -E '^I (companybi:|<none>)|^I mysql:8\.4\.5'; } | sort
}
fingerprint() { # state fingerprint of the tables a restore must bring back
  sql "CHECKSUM TABLE $DBNAME.company_bi_user, $DBNAME.company_bi_group, $DBNAME.company_bi_group_member, $DBNAME.company_bi_resource_acl, $DBNAME.data_visualization_info, $DBNAME.snapshot_data_visualization_info" | tr '\t' '=' | tr '\n' ' '
}
cleanup_stack() { # by explicit name only
  docker rm -f "$APP" >/dev/null 2>&1; docker rm -f "$DB" >/dev/null 2>&1; docker network rm "$NET" >/dev/null 2>&1
  if [ -d "$HOMEDIR" ]; then docker run --rm -v "$WORK:/t" --entrypoint /bin/sh mysql:8.4.5 -c 'rm -rf /t/home' >/dev/null 2>&1; fi
  return 0
}

# ================================================================ setup
say "setup: $V  (isolated project '$PROJECT', port $PORT, data $HOMEDIR)"
for c in companybi-app companybi-mysql; do docker inspect "$c" >/dev/null 2>&1 && { echo "production-named container '$c' exists on this host; refusing" >&2; exit 2; }; done
docker image inspect mysql:8.4.5 >/dev/null 2>&1 || echo "note: mysql:8.4.5 not present yet; it will be loaded from the tar"
if [ "$REUSE" = no ]; then cleanup_stack; rm -rf "$WORK/bundle" "$WORK/bad"; fi
snapshot > "$LOGS/snapshot-before.txt"
echo "other objects on this docker engine (must stay untouched): $(wc -l < "$LOGS/snapshot-before.txt")"

# ================================================================ S
if want S; then
  say "S. release smoke test on the formal artifacts"
  # --- S.1 integrity of the files themselves
  if ( cd "$DIST" && sha256sum -c "companybi_${V}_linux-amd64.tar.sha256" "companybi_deploy_${V}.tar.gz.sha256" "mysql_8.4.5_linux-amd64.tar.sha256" >/dev/null 2>&1 ); then
    res S.1 PASS "image tar, deploy bundle and mysql tar match their .sha256 files"
  else res S.1 FAIL "sha256 verification of the release files"; fi
  REC_TAR_SHA="$(sed -n 's/^artifact_tar_sha256=//p' "$REC")"; REAL_TAR_SHA="$(sha256sum "$TAR" | awk '{print $1}')"
  [ -n "$REC_TAR_SHA" ] && [ "$REC_TAR_SHA" = "$REAL_TAR_SHA" ] && res S.2 PASS "release record's artifact SHA256 equals the real tar" "${REAL_TAR_SHA:0:16}…" || res S.2 FAIL "release record vs real tar sha" "rec=$REC_TAR_SHA real=$REAL_TAR_SHA"
  REC_ID="$(sed -n 's/^image_id=//p' "$REC" | head -1)"
  [ "$(sed -n 's/^git_dirty=//p' "$REC" | head -1)" = clean ] && res S.2b PASS "release record says git_dirty=clean" || res S.2b FAIL "release record git_dirty"

  # --- S.3 import: remove OUR image tag (explicit name) so the install must come from the tar
  if docker image inspect "$IMG" >/dev/null 2>&1; then
    users="$(docker ps -a --filter "ancestor=$IMG" -q | wc -l)"
    [ "$users" = 0 ] || { echo "a container uses $IMG; refusing to remove the tag" >&2; exit 2; }
    docker rmi "$IMG" >/dev/null 2>&1
  fi
  docker image inspect "$IMG" >/dev/null 2>&1 && res S.3 FAIL "local tag $IMG removed before the install" || res S.3 PASS "local tag $IMG removed: the install below can only succeed from the tar"

  # --- S.4 extract the deploy bundle and use ITS scripts for everything
  rm -rf "$WORK/bundle"; mkdir -p "$WORK/bundle"
  tar -xzf "$BUNDLE" -C "$WORK/bundle" && [ -x "$BD/scripts/install.sh" ] && res S.4 PASS "deploy bundle extracted; scripts executable (no Git/Maven/Node needed)" || { res S.4 FAIL "bundle extract"; exit 1; }
  bash "$BD/scripts/install.sh" --init-env >/dev/null 2>&1
  sed -i -e "s|^COMPANY_BI_PROJECT=.*|COMPANY_BI_PROJECT=$PROJECT|" -e "s|^COMPANY_BI_HOME=.*|COMPANY_BI_HOME=$HOMEDIR|" \
         -e "s|^COMPANY_BI_PORT=.*|COMPANY_BI_PORT=$PORT|" \
         -e "s|^COMPANY_BI_ORIGIN_LIST=.*|COMPANY_BI_ORIGIN_LIST=http://localhost:$PORT,http://127.0.0.1:$PORT|" \
         -e "s|^COMPANY_BI_BOOTSTRAP_PASSWORD=.*|COMPANY_BI_BOOTSTRAP_PASSWORD=$BOOT_PW|" "$ENVF"
  # --- S.5 install on an EMPTY data dir
  T0=$(date +%s); S S5-install bd install.sh --version "$V" --image-tar "$TAR" --mysql-image-tar "$MTAR"; SECS=$(( $(date +%s) - T0 ))
  [ "$RC" = 0 ] && res S.5 PASS "install.sh from the tar exit 0" "${SECS}s" || { res S.5 FAIL "install.sh exit 0" "rc=$RC see logs/S5-install.log"; tail -15 "$LOGS/S5-install.log"; }
  NEW_ID="$(docker image inspect -f '{{.Id}}' "$IMG" 2>/dev/null)"
  [ -n "$REC_ID" ] && [ "$NEW_ID" = "$REC_ID" ] && res S.6 PASS "image imported from the tar has exactly the image ID in the release record" "${NEW_ID:0:23}…" || res S.6 FAIL "imported image ID vs release record" "rec=$REC_ID got=$NEW_ID"
  healthy 300 && res S.7 PASS "health-check.sh passes (containers, HTTP, API, admin row, no failed Flyway rows)" || res S.7 FAIL "health-check"

  # --- S.8 empty-DB initialisation evidence
  NF="$(sql "SELECT COUNT(*) FROM $DBNAME.de_standalone_version WHERE success=1")"; NMIG="$(docker exec "$APP" sh -c 'sed -n "s/^content.flyway.migrations=//p" /opt/apps/release/SCHEMA; true' 2>/dev/null)"
  NF_FAIL="$(sql "SELECT COUNT(*) FROM $DBNAME.de_standalone_version WHERE success=0")"
  NCO="$(sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DBNAME' AND table_name LIKE 'company\\_%'")"
  NCORE="$(sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DBNAME' AND table_name IN ('core_datasource','core_dataset_group','data_visualization_info','core_sys_setting')")"
  if [ "${NF:-0}" -ge 27 ] && [ "${NF_FAIL:-1}" = 0 ] && [ "${NCO:-0}" -ge 14 ] && [ "$NCORE" = 4 ]; then
    res S.8 PASS "fresh metadata DB initialised: Flyway success rows=$NF (0 failed), company tables=$NCO, core tables 4/4"
  else res S.8 FAIL "fresh DB init" "flyway_ok=$NF failed=$NF_FAIL company=$NCO core=$NCORE"; fi
  MYC="$(sql "SELECT CONCAT_WS('|', @@innodb_buffer_pool_size, @@transaction_isolation, @@time_zone)")"
  [ "$MYC" = "268435456|READ-COMMITTED|+08:00" ] && res S.8b PASS "mysql/my.cnf from the bundle is really applied" "$MYC" || res S.8b FAIL "my.cnf applied" "$MYC"

  # --- S.9 admin login + protected API
  ok_if S.9 "admin logs in with the bootstrap password; token opens a protected API; anonymous is rejected" "login/authed/anon" \
        bash -c "python3 '$SMOKE' login '$BASE' admin '$BOOT_PW' --save '$WORK/tok_admin' && python3 '$SMOKE' authed '$BASE' --token-file '$WORK/tok_admin' && python3 '$SMOKE' anon '$BASE'"

  # --- S.10 company custom APIs (all /de2api/company/bi/...)
  api GET /de2api/company/bi/me; ME="$APIBODY"
  [ "$APISTAT" = 200 ] && [ "$(jx 'd.get("username")' <<<"$ME")" = admin ] && res S.10 PASS "company API /company/bi/me returns the logged-in company user (admin)" "role=$(jx 'd.get("accountRole")' <<<"$ME")" || res S.10 FAIL "/company/bi/me" "HTTP $APISTAT $(echo "$ME" | cut -c1-160)"
  api GET /de2api/company/bi/admin/users; N_USERS="$(jx 'len(d)' <<<"$APIBODY")"
  [ "$APISTAT" = 200 ] && [ "${N_USERS:-0}" -ge 1 ] && res S.11 PASS "company admin API lists users (company_bi_user)" "users=$N_USERS" || res S.11 FAIL "/company/bi/admin/users" "HTTP $APISTAT $(echo "$APIBODY" | cut -c1-160)"
  api GET /de2api/company/bi/admin/permissions/catalog
  [ "$APISTAT" = 200 ] && res S.12 PASS "company permission catalog API answers" || res S.12 FAIL "/company/bi/admin/permissions/catalog" "HTTP $APISTAT $(echo "$APIBODY" | cut -c1-160)"
  python3 "$SMOKE" call "$BASE" --method GET --path /de2api/company/bi/admin/users 2>/dev/null | head -1 | grep -q -E 'HTTP (401|403)' && res S.13 PASS "company admin API refuses a call without a token" || res S.13 FAIL "company admin API must refuse anonymous callers" "$(python3 "$SMOKE" call "$BASE" --method GET --path /de2api/company/bi/admin/users 2>/dev/null | head -2 | tr '\n' ' ' | cut -c1-120)"
  FE="$(docker exec "$APP" printenv COMPANY_FEISHU_DRY_RUN 2>/dev/null)|$(docker exec "$APP" printenv COMPANY_FEISHU_INTERNAL_TOKEN 2>/dev/null)"
  [ "$FE" = "true|" ] && res S.14 PASS "Feishu forced to dry-run, no token in the running container" || res S.14 FAIL "Feishu safety env" "$FE"

  # --- S.15 dashboard
  DASH1="$(dash_make rv-smoke-dashboard)"; echo "$DASH1" > "$WORK/dash1.id"
  [ -n "$DASH1" ] && dash_see "$DASH1" rv-smoke-dashboard && res S.15 PASS "dashboard created and read back through the DataEase API" "id=$DASH1" || res S.15 FAIL "dashboard create/read"
  [ "$(sql "SELECT COUNT(*) FROM $DBNAME.data_visualization_info WHERE id=$DASH1")" = 1 ] && res S.16 PASS "dashboard row is in the dedicated metadata DB" || res S.16 FAIL "dashboard row in companybi_meta"

  # --- S.17 restart persistence
  ID0="$(app_id)"; docker restart "$APP" "$DB" >/dev/null 2>&1; healthy 300 && res S.17 PASS "both containers healthy after docker restart" || res S.17 FAIL "healthy after restart"
  dash_see "$DASH1" rv-smoke-dashboard && res S.18 PASS "dashboard still readable after the restart" || res S.18 FAIL "dashboard after restart"
  python3 "$SMOKE" authed "$BASE" --token-file "$WORK/tok_admin" >/dev/null 2>&1 && res S.19 PASS "admin token issued before the restart still valid" || res S.19 FAIL "token after restart"
  docker rm -f "$APP" "$DB" >/dev/null 2>&1; bd install.sh --help >/dev/null 2>&1
  # re-create both containers from compose exactly as the scripts do (bind mounts keep the data)
  ( . "$BD/scripts/lib.sh"; export COMPANY_BI_ENV_FILE="$ENVF"; load_env; compose up -d ) > "$LOGS/S19-recreate.log" 2>&1
  healthy 300 && dash_see "$DASH1" rv-smoke-dashboard && res S.20 PASS "after BOTH containers were deleted and re-created the dashboard is intact" || res S.20 FAIL "re-creation persistence" "see logs/S19-recreate.log"

  # --- S.21 version identification
  S S21-status bd status.sh
  [ "$(grep -c -- "$V" "$LOGS/S21-status.log" || true)" -ge 1 ] && res S.21 PASS "status.sh shows the running version $V" || res S.21 FAIL "status.sh version" "see logs/S21-status.log"
  bd health-check.sh --expect-version "$V" >/dev/null 2>&1 && res S.22 PASS "health-check.sh --expect-version $V passes" || res S.22 FAIL "expect-version"
  bd health-check.sh --expect-version "9.9.9-wrong" >/dev/null 2>&1 && res S.23 FAIL "a wrong expected version must be rejected" || res S.23 PASS "health-check.sh rejects a wrong expected version"

  # --- S.24 RELEASE metadata <-> real commit (EXPECT_SHA comes from `git rev-parse` of the release commit)
  RL="$(docker exec "$APP" cat /opt/apps/release/RELEASE 2>/dev/null)"
  g() { sed -n "s/^$1=//p" <<<"$RL" | head -1; }
  LBL_VER="$(docker inspect -f '{{index .Config.Labels "com.companybi.version"}}' "$APP")"
  LBL_SHA="$(docker inspect -f '{{index .Config.Labels "com.companybi.git-sha"}}' "$APP")"
  LBL_REV="$(docker inspect -f '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$APP")"
  LBL_DIRTY="$(docker inspect -f '{{index .Config.Labels "com.companybi.git-dirty"}}' "$APP")"
  REC_SHA="$(sed -n 's/^git_sha=//p' "$REC" | head -1)"
  JAR_IN="$(docker exec "$APP" sh -c 'sha256sum /opt/apps/app.jar 2>/dev/null | cut -d" " -f1' 2>/dev/null)"
  EV="RELEASE.git_sha=${EXPECT_SHA:0:10} version=$V"
  if [ "$(g git_sha)" = "$EXPECT_SHA" ] && [ "$LBL_SHA" = "$EXPECT_SHA" ] && [ "$LBL_REV" = "$EXPECT_SHA" ] && [ "$REC_SHA" = "$EXPECT_SHA" ] \
     && [ "$(g version)" = "$V" ] && [ "$LBL_VER" = "$V" ] && [ "$(g git_dirty)" = clean ] && [ "$LBL_DIRTY" = clean ] \
     && [[ "$V" == *"-${EXPECT_SHA:0:8}" ]]; then
    res S.24 PASS "RELEASE file, image labels, release record and version suffix all name the real commit; git_dirty=clean" "$EV"
  else res S.24 FAIL "RELEASE metadata vs commit" "release=$(g git_sha) label=$LBL_SHA oci=$LBL_REV record=$REC_SHA dirty=$(g git_dirty)/$LBL_DIRTY expected=$EXPECT_SHA"; fi
  [ -n "$JAR_IN" ] && [ "$JAR_IN" = "$(g jar_sha256)" ] && res S.25 PASS "RELEASE.jar_sha256 equals the sha256 of the jar actually inside the running container" "${JAR_IN:0:16}…" || res S.25 FAIL "jar sha256 inside container" "in=$JAR_IN release=$(g jar_sha256)"
fi

# ================================================================ R
if want R; then
  say "R. real database restore on an isolated stack (test data only)"
  [ -s "$WORK/tok_admin" ] || login_as admin "$BOOT_PW" "$WORK/tok_admin"
  PW_A="Rst-UserA-Pass-2026"; PW_A2="Rst-UserA-Changed-2026"; PW_B="Rst-UserB-Pass-2026"
  # clean leftovers of a previous run on a reused stack
  # ---- R.1 create state: user A (NORMAL) + permission on dashboard A, dashboard B (no grant for A)
  api POST /de2api/company/bi/admin/users '{"username":"rst_user_a","displayName":"Restore Test A","accountRole":"NORMAL","status":"ENABLED","password":"'"$PW_A"'"}'
  UID_A="$(jx 'd.get("id")' <<<"$APIBODY")"
  [ "$APISTAT" = 200 ] && [ -n "$UID_A" ] && res R.1 PASS "test user rst_user_a created through the company admin API" "id=$UID_A" || { res R.1 FAIL "create user A" "HTTP $APISTAT $(echo "$APIBODY" | cut -c1-200)"; }
  DA="$(dash_make rst-dash-A)"; DB_="$(dash_make rst-dash-B)"
  [ -n "$DA" ] && [ -n "$DB_" ] && res R.2 PASS "two test dashboards created (A, B)" "A=$DA B=$DB_" || res R.2 FAIL "create dashboards" "A=$DA B=$DB_"
  api POST /de2api/company/bi/admin/permissions/grant '{"subjectType":"USER","subjectId":'"${UID_A:-0}"',"resourceType":"DASHBOARD","resourceId":'"${DA:-0}"',"relation":"VISITOR"}'
  [ "$APISTAT" = 200 ] && res R.3 PASS "permission granted: rst_user_a is VISITOR of dashboard A" "$(jx 'd.get("result")' <<<"$APIBODY")" || res R.3 FAIL "grant ACL" "HTTP $APISTAT $(echo "$APIBODY" | cut -c1-200)"
  login_as rst_user_a "$PW_A" "$WORK/tok_a" && res R.4 PASS "rst_user_a can log in" || res R.4 FAIL "user A login"
  dash_see "$DA" rst-dash-A "$WORK/tok_a" && res R.5 PASS "rst_user_a (granted) can read dashboard A" || res R.5 FAIL "user A reads dashboard A"
  if dash_see "$DB_" rst-dash-B "$WORK/tok_a"; then res R.5b PASS "NOTE: rst_user_a can also read dashboard B (no grant) - baseline recorded, not used as a restore criterion" "informational"
  else res R.5b PASS "rst_user_a (no grant) cannot read dashboard B - permission baseline recorded"; fi
  api GET "/de2api/company/bi/admin/permissions/grants?subjectType=USER&subjectId=$UID_A"
  [ "$(jx 'sum(1 for x in d if str(x.get("resourceId"))=="'"$DA"'")' <<<"$APIBODY")" = 1 ] && res R.6 PASS "grants API shows A's permission on dashboard A" || res R.6 FAIL "grants list" "$(echo "$APIBODY" | cut -c1-200)"

  # ---- R.7 backup with the formal script
  S R7-backup bd backup-db.sh --label restore-test
  BK="$(sed -n 's/^.*backup written: //p;s/^backup: //p' "$LOGS/R7-backup.log" | tail -1)"
  [ -s "$BK" ] || BK="$(ls -t "$HOMEDIR"/backups/*restore-test*.sql.gz 2>/dev/null | head -1)"
  if [ "$RC" = 0 ] && [ -s "$BK" ] && gzip -t "$BK" 2>/dev/null && gzhas "$BK" 'rst_user_a' && [ "$(stat -c %a "$BK")" = 600 ]; then
    res R.7 PASS "backup-db.sh made a valid gz (mode 600) that contains the test user" "$(basename "$BK") $(stat -c %s "$BK")B"
  else res R.7 FAIL "backup" "rc=$RC file=$BK see logs/R7-backup.log"; fi
  FP0="$(fingerprint)"; echo "$FP0" > "$LOGS/fingerprint-at-backup.txt"
  CNT0="users=$(sql "SELECT COUNT(*) FROM $DBNAME.company_bi_user") acl=$(sql "SELECT COUNT(*) FROM $DBNAME.company_bi_resource_acl") dash=$(sql "SELECT COUNT(*) FROM $DBNAME.data_visualization_info")"
  res R.8 PASS "state fingerprint (table checksums) recorded at backup time" "$CNT0"

  # ---- R.9 controlled damage AFTER the backup
  api POST /de2api/company/bi/admin/users '{"username":"rst_user_b","displayName":"Created After Backup","accountRole":"NORMAL","status":"ENABLED","password":"'"$PW_B"'"}'; UID_B="$(jx 'd.get("id")' <<<"$APIBODY")"
  api POST /de2api/company/bi/admin/permissions/revoke '{"subjectType":"USER","subjectId":'"${UID_A:-0}"',"resourceType":"DASHBOARD","resourceId":'"${DA:-0}"'}'
  api POST "/de2api/company/bi/admin/users/$UID_A/role" '{"accountRole":"READONLY"}'
  api POST "/de2api/company/bi/admin/users/$UID_A/resetPassword" '{"password":"'"$PW_A2"'"}'
  DC="$(dash_make rst-dash-C-after-backup)"
  sql "DELETE FROM $DBNAME.data_visualization_info WHERE id=$DA; DELETE FROM $DBNAME.snapshot_data_visualization_info WHERE id=$DA; CREATE TABLE $DBNAME.rst_newer_version_table (id INT PRIMARY KEY); INSERT INTO $DBNAME.rst_newer_version_table VALUES (1);" >/dev/null
  BAD=0
  [ "$(sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DBNAME' AND table_name='rst_newer_version_table'")" = 1 ] || BAD=7
  [ "$(sql "SELECT COUNT(*) FROM $DBNAME.company_bi_user WHERE username='rst_user_b'")" = 1 ] || BAD=1
  [ "$(sql "SELECT account_role FROM $DBNAME.company_bi_user WHERE username='rst_user_a'")" = READONLY ] || BAD=2
  [ "$(sql "SELECT COUNT(*) FROM $DBNAME.company_bi_resource_acl WHERE subject_id=$UID_A")" = 0 ] || BAD=3
  [ "$(sql "SELECT COUNT(*) FROM $DBNAME.data_visualization_info WHERE id=$DA")" = 0 ] || BAD=4
  [ "$(sql "SELECT COUNT(*) FROM $DBNAME.data_visualization_info WHERE id=$DC")" = 1 ] || BAD=5
  login_as rst_user_a "$PW_A" && BAD=6
  [ "$BAD" = 0 ] && res R.9 PASS "damage applied: user B added, A's role changed + password reset, A's grant revoked, dashboard A deleted, dashboard C added, a table that did not exist at backup time created (stands for a newer schema), old password rejected" || res R.9 FAIL "damage was not applied as intended" "step=$BAD"
  [ "$(fingerprint)" != "$FP0" ] && res R.10 PASS "state fingerprint now DIFFERS from the backup-time one (damage is real)" || res R.10 FAIL "fingerprint should differ after damage"
  ID_BEFORE_RESTORE="$(app_id)"

  # ---- R.11 negative: broken backup files are refused and change nothing
  mkdir -p "$WORK/bad"; echo "this is not a dump" | gzip > "$WORK/bad/notadump.sql.gz"; echo "plain text" > "$WORK/bad/notgz.sql.gz"
  S R11a bd rollback.sh --restore-db "$WORK/bad/notadump.sql.gz" --yes; RCa=$RC
  S R11b bd rollback.sh --restore-db "$WORK/bad/notgz.sql.gz" --yes; RCb=$RC
  [ "$RCa" != 0 ] && [ "$RCb" != 0 ] && [ "$(fingerprint)" != "$FP0" ] && [ "$(app_id)" = "$ID_BEFORE_RESTORE" ] && [ "$(sql "SELECT COUNT(*) FROM $DBNAME.company_bi_user WHERE username='rst_user_b'")" = 1 ] \
    && res R.11 PASS "a non-dump gz and a non-gz file are both refused; DB and containers untouched" "rc=$RCa/$RCb" || res R.11 FAIL "bad backup files must be refused without side effects" "rc=$RCa/$RCb"

  # ---- R.12 restore with the formal script
  T0=$(date +%s); S R12-restore bd rollback.sh --restore-db "$BK" --yes; SECS=$(( $(date +%s) - T0 ))
  [ "$RC" = 0 ] && res R.12 PASS "rollback.sh --restore-db exit 0" "${SECS}s" || { res R.12 FAIL "restore exit code" "rc=$RC see logs/R12-restore.log"; tail -12 "$LOGS/R12-restore.log"; }
  FP1="$(fingerprint)"
  [ "$FP1" = "$FP0" ] && res R.13 PASS "table checksums after the restore are IDENTICAL to the backup-time ones (user, group, ACL, dashboards)" || res R.13 FAIL "fingerprint after restore" "before=$FP0 after=$FP1"
  [ "$(sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DBNAME' AND table_name='rst_newer_version_table'")" = 0 ] && res R.13b PASS "a table created after the backup (stands for a newer schema) is GONE after the script restore - the DB is back to the backup-time structure, not just the backup-time rows" || res R.13b FAIL "table created after the backup survived the restore"
  SAFE="$(st LAST_BACKUP)"
  [ -s "$SAFE" ] && [ "$SAFE" != "$BK" ] && gzhas "$SAFE" 'rst_user_b' && gzhas "$SAFE" 'rst-dash-C-after-backup' && res R.14 PASS "a safety dump of the damaged DB was taken BEFORE the restore and still holds the post-backup data" "$(basename "$SAFE")" || res R.14 FAIL "safety dump" "$SAFE"
  [ "$(st LAST_OP_RESULT)" = OK ] && [ "$(st PENDING_MANUAL)" = no ] && res R.15 PASS "state after restore: LAST_OP_RESULT=OK, PENDING_MANUAL=no" || res R.15 FAIL "state" "$(st LAST_OP_RESULT)/$(st PENDING_MANUAL)"
  [ "$(app_id)" != "$ID_BEFORE_RESTORE" ] && res R.15b PASS "app container was re-created by the restore (no stale in-memory state)" || res R.15b FAIL "app container re-created"

  # ---- R.16 DataEase works on the restored DB
  healthy 300 && res R.16 PASS "health-check.sh passes after the restore" || res R.16 FAIL "health after restore"
  login_as admin "$BOOT_PW" "$WORK/tok_admin" && python3 "$SMOKE" authed "$BASE" --token-file "$WORK/tok_admin" >/dev/null 2>&1 && res R.17 PASS "admin can log in and call protected APIs on the restored DB" || res R.17 FAIL "admin after restore"
  login_as rst_user_a "$PW_A" "$WORK/tok_a" && res R.18 PASS "rst_user_a is back and the ORIGINAL password works" || res R.18 FAIL "user A original password"
  login_as rst_user_a "$PW_A2" && res R.19 FAIL "the post-backup password must no longer work" || res R.19 PASS "the post-backup password no longer works"
  [ "$(sql "SELECT account_role FROM $DBNAME.company_bi_user WHERE username='rst_user_a'")" = NORMAL ] && [ "$(sql "SELECT COUNT(*) FROM $DBNAME.company_bi_user WHERE username='rst_user_b'")" = 0 ] && res R.20 PASS "role restored to NORMAL; user B (created after the backup) is gone" || res R.20 FAIL "role/user B"
  api GET "/de2api/company/bi/admin/permissions/grants?subjectType=USER&subjectId=$UID_A"
  [ "$(jx 'sum(1 for x in d if str(x.get("resourceId"))=="'"$DA"'")' <<<"$APIBODY")" = 1 ] && res R.21 PASS "A's permission on dashboard A is back (read through the company API)" || res R.21 FAIL "grant after restore" "$(echo "$APIBODY" | cut -c1-200)"
  dash_see "$DA" rst-dash-A && dash_see "$DA" rst-dash-A "$WORK/tok_a" && res R.22 PASS "dashboard A is readable again, by admin and by rst_user_a" || res R.22 FAIL "dashboard A after restore"
  dash_see "$DC" rst-dash-C-after-backup && res R.23 FAIL "dashboard C (created after the backup) must be gone" || res R.23 PASS "dashboard C (created after the backup) is gone"
  api GET /de2api/company/bi/admin/users; [ "$APISTAT" = 200 ] && [ "$(jx '",".join(sorted(u["username"] for u in d))' <<<"$APIBODY")" = "admin,rst_user_a" ] && res R.24 PASS "company user list = exactly the backup-time users (admin, rst_user_a)" || res R.24 FAIL "user list" "$(jx '",".join(sorted(u["username"] for u in d))' <<<"$APIBODY")"
  # the restored DB must still accept writes (grants of the app DB user survived DROP/CREATE DATABASE)
  DD="$(dash_make rst-dash-D-written-after-restore)"; [ -n "$DD" ] && dash_see "$DD" rst-dash-D-written-after-restore && res R.25 PASS "the app can WRITE to the restored database (DB user grants intact)" || res R.25 FAIL "write after restore"

  # ---- R.26 the SCRIPT-LESS procedure of docs/rollback.md section 2.7, executed literally
  api POST /de2api/company/bi/admin/users '{"username":"rst_user_c","displayName":"Damage Round 2","accountRole":"NORMAL","status":"ENABLED","password":"Rst-UserC-Pass-2026"}'
  sql "CREATE TABLE $DBNAME.rst_newer_version_table (id INT PRIMARY KEY); INSERT INTO $DBNAME.rst_newer_version_table VALUES (1);" >/dev/null
  [ "$(sql "SELECT COUNT(*) FROM $DBNAME.company_bi_user WHERE username='rst_user_c'")" = 1 ] && [ "$(fingerprint)" != "$FP0" ] && res R.25b PASS "round-2 damage applied (user C exists, state differs from the backup)" || res R.25b FAIL "round-2 damage not applied"
  # take the command block of section 2.7 straight out of the doc shipped in the deploy bundle, substitute only
  # the install path / project / container / home / backup file, and run it unchanged otherwise
  MAN="$WORK/manual-2.7.sh"
  awk '/^### 2\.7/{f=1;next} f&&/^```bash/{b=1;next} f&&b&&/^```/{exit} f&&b{print}' "$BD/docs/rollback.md" \
    | grep -v -E '^\s*(#|$)' \
    | sed -e "s|/opt/companybi|$BD|g" -e "s|companybi-mysql|$DB|g" -e "s|--project-name companybi|--project-name $PROJECT|g" \
          -e "s|\${COMPANY_BI_HOME}|$HOMEDIR|g" -e "s|<备份文件>|$BK|g" > "$MAN"
  M_OUT="$LOGS/R26-manual.log"
  NCMD="$(grep -c . "$MAN" || true)"
  if [ "${NCMD:-0}" -ge 4 ]; then bash -e "$MAN" > "$M_OUT" 2>&1; MRC=$?; else MRC=99; echo "could not extract the 2.7 command block" > "$M_OUT"; fi
  [ "$MRC" = 0 ] && res R.26 PASS "the manual recovery commands of docs/rollback.md 2.7 (extracted from the bundle's own doc, $NCMD commands) ran without error" || res R.26 FAIL "manual recovery commands" "rc=$MRC see logs/R26-manual.log and $MAN"
  healthy 300 && res R.27 PASS "stack healthy after the manual recovery" || res R.27 FAIL "healthy after manual recovery"
  [ "$(fingerprint)" = "$FP0" ] && [ "$(sql "SELECT COUNT(*) FROM $DBNAME.company_bi_user WHERE username='rst_user_c'")" = 0 ] && res R.28 PASS "after the manual procedure the table checksums equal the backup-time state again (user C gone, no leftover tables/rows)" || res R.28 FAIL "fingerprint after manual recovery" "now=$(fingerprint)"
  [ "$(sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DBNAME' AND table_name='rst_newer_version_table'")" = 0 ] && res R.28b PASS "the manual procedure also removed the table that did not exist at backup time (needs the DROP DATABASE step in docs/rollback.md 2.7)" || res R.28b FAIL "manual procedure left a post-backup table behind: docs/rollback.md 2.7 lacks the DROP DATABASE step"
  login_as admin "$BOOT_PW" "$WORK/tok_admin" && dash_see "$DA" rst-dash-A && res R.29 PASS "login and dashboard read work after the manual recovery" || res R.29 FAIL "login/dashboard after manual recovery"
fi

# ================================================================ end
say "scope check and cleanup"
snapshot > "$LOGS/snapshot-after.txt"
if diff -q "$LOGS/snapshot-before.txt" "$LOGS/snapshot-after.txt" >/dev/null 2>&1; then res Z.1 PASS "no other container, network, volume or image changed during this verification" "$(wc -l < "$LOGS/snapshot-before.txt") foreign objects compared"
else res Z.1 FAIL "foreign docker objects changed" "$(diff "$LOGS/snapshot-before.txt" "$LOGS/snapshot-after.txt" | head -5 | tr '\n' ';')"; fi
if [ "$KEEP" = no ]; then cleanup_stack; rm -rf "$WORK/bundle" "$WORK/bad" "$WORK/tok_"* "$WORK/dash1.id"; echo "removed $PROJECT containers/network/data by explicit name"; else echo "stack kept (--keep): docker rm -f $APP $DB; docker network rm $NET; rm -rf $WORK"; fi
echo; echo "=== summary ==="
P=$(grep -c -P '\tPASS\t' "$RES" || true); F=$(grep -c -P '\tFAIL\t' "$RES" || true)
echo "PASS=$P FAIL=$F  (details: $RES)"
[ "$F" = 0 ]
