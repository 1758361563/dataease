#!/usr/bin/env bash
# Shared helpers for the Company BI operations scripts. Source it, do not execute it.
#
# Hard rules baked in here (see docs/deployment.md):
#   * every docker object this tooling touches is named <project>-app / <project>-mysql / <project>-net;
#   * docker compose is only ever invoked through compose(), which allows a short list of verbs
#     and rejects `down`, `-v`, `--remove-orphans`, `--rmi`;
#   * nothing here runs system/volume/image/network prune, or lists-then-removes "all" anything;
#   * secrets are never echoed.
#
# Compatible with bash >= 4.2 (CentOS 7 / Alibaba Cloud Linux) and GNU coreutils.

LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(cd "$LIB_DIR/.." && pwd)"
ENV_FILE="${COMPANY_BI_ENV_FILE:-$DEPLOY_DIR/.env}"

log()  { printf '%s %s\n' "$(date '+%F %T')" "$*"; }
warn() { log "WARN:  $*" >&2; }
die()  { log "ERROR: $*" >&2; exit 1; }
hr()   { printf '%s\n' "----------------------------------------------------------------"; }

# ---------------------------------------------------------------------------
# .env loading (literal KEY=VALUE, no shell evaluation)
# ---------------------------------------------------------------------------
load_env() {
  [ -f "$ENV_FILE" ] || die ".env not found: $ENV_FILE  (copy .env.example, or run install.sh --init-env)"
  local perm line key val
  perm="$(stat -c %a "$ENV_FILE" 2>/dev/null || echo '')"
  case "$perm" in 600|400) ;; *) warn ".env permissions are '$perm'; run: chmod 600 $ENV_FILE" ;; esac
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%$'\r'}"
    case "$line" in ''|'#'*) continue ;; esac
    case "$line" in *=*) ;; *) continue ;; esac
    key="${line%%=*}"; val="${line#*=}"
    key="${key//[[:space:]]/}"
    [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
    val="${val#"${val%%[![:space:]]*}"}"; val="${val%"${val##*[![:space:]]}"}"
    if [[ "$val" =~ ^\".*\"$ ]] || [[ "$val" =~ ^\'.*\'$ ]]; then val="${val:1:${#val}-2}"; fi
    export "$key=$val"
  done < "$ENV_FILE"

  PROJECT="${COMPANY_BI_PROJECT:-companybi}"
  [[ "$PROJECT" =~ ^[a-z][a-z0-9_-]{2,30}$ ]] || die "COMPANY_BI_PROJECT '$PROJECT' is invalid (lowercase letters, digits, - _ ; 3-31 chars)"
  export COMPANY_BI_PROJECT="$PROJECT"
  [ -n "${COMPANY_BI_HOME:-}" ] || die "COMPANY_BI_HOME is not set in $ENV_FILE"
  case "$COMPANY_BI_HOME" in /*) ;; *) die "COMPANY_BI_HOME must be an absolute path" ;; esac
  case "$COMPANY_BI_HOME" in /|/usr|/usr/*|/etc|/etc/*|/var|/bin|/boot|/root|/home|/opt|/lib*|/sbin|/dev|/proc|/sys) die "COMPANY_BI_HOME '$COMPANY_BI_HOME' is a system path" ;; esac

  APP_C="${PROJECT}-app"; DB_C="${PROJECT}-mysql"; NET_N="${PROJECT}-net"
  PORT="${COMPANY_BI_PORT:-30005}"; BIND="${COMPANY_BI_BIND_ADDR:-127.0.0.1}"
  DB_NAME="${COMPANY_BI_DB_NAME:-companybi_meta}"
  REL_DIR="$COMPANY_BI_HOME/releases"; BAK_DIR="$COMPANY_BI_HOME/backups"; IMG_DIR="$COMPANY_BI_HOME/images"
  VERSION_ENV="$REL_DIR/version.env"; STATE_FILE="$REL_DIR/state.env"; HISTORY_FILE="$REL_DIR/history.tsv"
}

need_cmds() { local c; for c in "$@"; do command -v "$c" >/dev/null 2>&1 || die "required command not found: $c"; done; }

check_docker() {
  need_cmds docker awk sed grep sha256sum
  docker info >/dev/null 2>&1 || die "docker daemon is not reachable by this user"
  docker compose version >/dev/null 2>&1 || die "docker compose v2 plugin is required ('docker compose version' failed)"
}

# ---------------------------------------------------------------------------
# docker compose, project-scoped and verb-restricted
# ---------------------------------------------------------------------------
compose() {
  local verb="${1:-}" a
  case "$verb" in config|ps|logs|up|stop|start|restart) ;; *) die "compose verb '$verb' is not allowed by the Company BI scripts" ;; esac
  for a in "$@"; do
    case "$a" in -v|--volumes|--remove-orphans|--rmi|down|rm|kill) die "compose argument '$a' is not allowed by the Company BI scripts" ;; esac
  done
  local envs=()
  [ -f "$VERSION_ENV" ] && envs=(--env-file "$VERSION_ENV")
  docker compose --project-name "$PROJECT" --project-directory "$DEPLOY_DIR" -f "$DEPLOY_DIR/compose.yaml" \
    --env-file "$ENV_FILE" ${envs[@]+"${envs[@]}"} "$@"
}

container_exists()  { docker inspect "$1" >/dev/null 2>&1; }
container_running() { [ "$(docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null || echo false)" = "true" ]; }
container_project() { docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' "$1" 2>/dev/null || true; }
container_image_id(){ docker inspect -f '{{.Image}}' "$1" 2>/dev/null || true; }
container_health()  { docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$1" 2>/dev/null || echo missing; }

# Refuse to touch a same-named container that does not belong to our compose project.
assert_ours() {
  local c="$1" p
  container_exists "$c" || return 0
  p="$(container_project "$c")"
  [ "$p" = "$PROJECT" ] || die "container '$c' exists but belongs to compose project '${p:-<none>}', not '$PROJECT'. Refusing to touch it."
}

mysql_exec() { # mysql_exec "<sql>"  -> stdout, uses root password inside the container, never on a command line
  docker exec -i "$DB_C" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -N -B -uroot' <<<"$1"
}

# ---------------------------------------------------------------------------
# locking and release state
# ---------------------------------------------------------------------------
acquire_lock() {
  mkdir -p "$REL_DIR"
  if command -v flock >/dev/null 2>&1; then
    exec 9>"$REL_DIR/.lock"
    flock -n 9 || die "another Company BI operation is running (lock: $REL_DIR/.lock)"
  else
    mkdir "$REL_DIR/.lock.d" 2>/dev/null || die "another Company BI operation is running (lock dir: $REL_DIR/.lock.d)"
    trap 'rmdir "$REL_DIR/.lock.d" 2>/dev/null || true' EXIT
  fi
}

state_get() { [ -f "$STATE_FILE" ] && sed -n "s/^$1=//p" "$STATE_FILE" | tail -1 || true; }
state_set() { # state_set KEY VALUE ...
  mkdir -p "$REL_DIR"
  local tmp; tmp="$(mktemp "$REL_DIR/.state.XXXXXX")"
  [ -f "$STATE_FILE" ] && cp "$STATE_FILE" "$tmp"
  while [ $# -ge 2 ]; do
    grep -v "^$1=" "$tmp" > "$tmp.n" || true
    printf '%s=%s\n' "$1" "$2" >> "$tmp.n"
    mv "$tmp.n" "$tmp"
    shift 2
  done
  chmod 600 "$tmp"; mv "$tmp" "$STATE_FILE"
}
version_env_set() { mkdir -p "$REL_DIR"; printf 'COMPANY_BI_VERSION=%s\n' "$1" > "$VERSION_ENV.tmp"; mv "$VERSION_ENV.tmp" "$VERSION_ENV"; }
version_env_get() { [ -f "$VERSION_ENV" ] && sed -n 's/^COMPANY_BI_VERSION=//p' "$VERSION_ENV" | tail -1 || true; }

history_add() { # event from to result schema backup note
  mkdir -p "$REL_DIR"
  [ -f "$HISTORY_FILE" ] || printf 'time\tevent\tfrom\tto\tresult\tschema\tbackup\tnote\n' > "$HISTORY_FILE"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$(date '+%F %T')" "${1:-}" "${2:--}" "${3:--}" "${4:-}" "${5:--}" "${6:--}" "${7:--}" >> "$HISTORY_FILE"
}

# ---------------------------------------------------------------------------
# images and release metadata
# ---------------------------------------------------------------------------
image_ref() { printf 'companybi:%s' "$1"; }
image_id()  { docker image inspect -f '{{.Id}}' "$(image_ref "$1")" 2>/dev/null || true; }
image_platform() { docker image inspect -f '{{.Os}}/{{.Architecture}}' "$(image_ref "$1")" 2>/dev/null || true; }
image_label() { docker image inspect -f "{{index .Config.Labels \"$2\"}}" "$(image_ref "$1")" 2>/dev/null || true; }
image_read() { # image_read <version> <file under /opt/apps/release>
  docker run --rm --network none --entrypoint /bin/cat "$(image_ref "$1")" "/opt/apps/release/$2"
}
kv() { sed -n "s/^$2=//p" "$1" | head -1; }

store_release_files() { # store_release_files <version>   (copies SCHEMA/RELEASE/COMPAT out of the image)
  local v="$1" d="$REL_DIR/$1" f
  mkdir -p "$d"
  for f in RELEASE SCHEMA COMPAT; do image_read "$v" "$f" > "$d/$f.tmp" && mv "$d/$f.tmp" "$d/$f"; done
}

# schema_delta <old SCHEMA> <new SCHEMA>  ->  lines "added|changed|removed <key>" (fingerprint and risk lines excluded)
schema_delta() {
  awk '
    FNR==NR { if ($0 !~ /^(schema\.fingerprint|risk\.)/) { k=$0; sub(/=.*/,"",k); v=$0; sub(/^[^=]*=/,"",v); old[k]=v } next }
    $0 ~ /^(schema\.fingerprint|risk\.)/ { next }
    { k=$0; sub(/=.*/,"",k); v=$0; sub(/^[^=]*=/,"",v); seen[k]=1
      if (!(k in old)) print "added " k; else if (old[k]!=v) print "changed " k }
    END { for (k in old) if (!(k in seen)) print "removed " k }
  ' "$1" "$2" | sort
}

# schema_assess <old SCHEMA> <new SCHEMA> <new COMPAT>
# sets: SCHEMA_CLASS   NONE | DECLARED_COMPATIBLE | UNCONFIRMED
#       SCHEMA_DESTRUCTIVE  yes|no      SCHEMA_DELTA  (multi-line text)
schema_assess() {
  local old="$1" new="$2" compat="$3" ofp nfp line key kind risk
  ofp="$(kv "$old" schema.fingerprint)"; nfp="$(kv "$new" schema.fingerprint)"
  [ -n "$ofp" ] && [ -n "$nfp" ] || { SCHEMA_CLASS=UNCONFIRMED; SCHEMA_DESTRUCTIVE=unknown; SCHEMA_DELTA="(schema manifest missing)"; return; }
  if [ "$ofp" = "$nfp" ]; then SCHEMA_CLASS=NONE; SCHEMA_DESTRUCTIVE=no; SCHEMA_DELTA=""; return; fi
  SCHEMA_DELTA="$(schema_delta "$old" "$new")"
  SCHEMA_DESTRUCTIVE=no
  while IFS= read -r line; do
    kind="${line%% *}"; key="${line#* }"
    case "$kind" in
      removed) SCHEMA_DESTRUCTIVE=yes ;;
      added|changed)
        case "$key" in
          flyway.file.*)  risk="risk.flyway.${key#flyway.file.}" ;;
          company.sql.*)  risk="risk.company.${key#company.sql.}" ;;
          company.initializer) risk="risk.company.initializer" ;;
          *) risk="" ;;
        esac
        [ -n "$risk" ] && grep -q "^$risk=" "$new" && SCHEMA_DESTRUCTIVE=yes
        ;;
    esac
  done <<<"$SCHEMA_DELTA"
  if [ -f "$compat" ] && grep -E '^[0-9a-f]{64}([[:space:]]|$)' "$compat" | awk '{print $1}' | grep -qx "$ofp"; then
    SCHEMA_CLASS=DECLARED_COMPATIBLE
  else
    SCHEMA_CLASS=UNCONFIRMED
  fi
}

# ---------------------------------------------------------------------------
# sizes / host resources
# ---------------------------------------------------------------------------
to_mib() { # 2g -> 2048 ; 768m -> 768 ; 1536 (bytes) not supported on purpose
  local v="${1,,}"
  case "$v" in
    *g) echo $(( ${v%g} * 1024 )) ;;
    *m) echo "${v%m}" ;;
    *) die "unsupported memory size '$1' (use e.g. 2g or 768m)" ;;
  esac
}
mem_available_mib() { awk '/^MemAvailable:/ {printf "%d", $2/1024}' /proc/meminfo; }
free_mib_at() { local p="$1"; while [ ! -d "$p" ] && [ "$p" != "/" ]; do p="$(dirname "$p")"; done; df -Pk "$p" | awk 'NR==2 {printf "%d", $4/1024}'; }
container_mem_mib() { # current usage of a running container in MiB (0 if not running)
  container_running "$1" || { echo 0; return; }
  docker stats --no-stream --format '{{.MemUsage}}' "$1" 2>/dev/null | awk '{
    v=$1; u=v; gsub(/[0-9.]/,"",u); n=v; gsub(/[A-Za-z]/,"",n);
    if (u=="GiB") printf "%d", n*1024; else if (u=="MiB") printf "%d", n; else if (u=="KiB") printf "%d", n/1024; else printf "0" }' || echo 0
}
port_in_use() { # TCP listener on this port (any address)
  if command -v ss >/dev/null 2>&1; then ss -ltnH 2>/dev/null | awk '{print $4}' | grep -qE "[:.]$1\$"
  else grep -qiE ":$(printf '%04X' "$1") " /proc/net/tcp /proc/net/tcp6 2>/dev/null; fi
}

# ---------------------------------------------------------------------------
# HTTP (curl, wget, or wget inside the app container)
# ---------------------------------------------------------------------------
http_code() { # http_code <url>  -> status code (000 on failure)
  if command -v curl >/dev/null 2>&1; then curl -s -o /dev/null -w '%{http_code}' --max-time 8 "$1" || echo 000
  elif command -v wget >/dev/null 2>&1; then
    wget -q -T 8 --spider -S "$1" 2>&1 | awk '/HTTP\//{c=$2} END{print (c?c:"000")}'
  else echo 000; fi
}
http_body() { # http_body <url>
  if command -v curl >/dev/null 2>&1; then curl -s --max-time 8 "$1" || true
  elif command -v wget >/dev/null 2>&1; then wget -q -T 8 -O - "$1" || true
  fi
}

confirm() { # confirm "<question>"  (typed yes); non-interactive runs must pass --yes to callers
  local ans; printf '%s [type "yes" to continue]: ' "$1" >&2; read -r ans || ans=""
  [ "$ans" = "yes" ]
}

# ---------------------------------------------------------------------------
# shared install/upgrade building blocks
# ---------------------------------------------------------------------------
require_secrets() {
  local k v
  for k in COMPANY_BI_DB_PASSWORD COMPANY_BI_DB_ROOT_PASSWORD COMPANY_BI_TOKEN_SECRET; do
    v="${!k:-}"
    [ -n "$v" ] || die "$k is empty in $ENV_FILE"
    [ "${#v}" -ge 16 ] || die "$k is shorter than 16 characters"
  done
  if [ "$COMPANY_BI_DB_PASSWORD" = "$COMPANY_BI_DB_ROOT_PASSWORD" ]; then die "COMPANY_BI_DB_PASSWORD and COMPANY_BI_DB_ROOT_PASSWORD must differ"; fi
  case "$COMPANY_BI_DB_PASSWORD$COMPANY_BI_DB_ROOT_PASSWORD$COMPANY_BI_TOKEN_SECRET" in
    *[\$\`\"\'\\#\ ]*) die "secrets in $ENV_FILE contain a forbidden character (\$ \` \" ' \\ # or space)" ;;
  esac
}

ensure_dirs() {
  umask 077
  mkdir -p "$COMPANY_BI_HOME/mysql/data" "$COMPANY_BI_HOME/app/data" "$COMPANY_BI_HOME/app/conf" \
           "$COMPANY_BI_HOME/app/cache" "$COMPANY_BI_HOME/app/logs" "$REL_DIR" "$BAK_DIR" "$IMG_DIR"
  chmod 700 "$COMPANY_BI_HOME" "$BAK_DIR" "$REL_DIR"
  # app dirs are written by the container user (root in this image); keep them private to the host owner/root
  chmod 755 "$COMPANY_BI_HOME/app" "$COMPANY_BI_HOME/app/data" "$COMPANY_BI_HOME/app/conf" "$COMPANY_BI_HOME/app/cache" "$COMPANY_BI_HOME/app/logs"
}

# load_image_tar <file>: verify <file>.sha256 when present (or --sha256 given), then docker load.
load_image_tar() {
  local f="$1" want got
  [ -f "$f" ] || die "image tar not found: $f"
  if [ -f "$f.sha256" ]; then
    want="$(awk '{print $1}' "$f.sha256" | head -1)"
    got="$(sha256sum "$f" | awk '{print $1}')"
    [ "$want" = "$got" ] || die "SHA256 mismatch for $f (expected $want, got $got). Do NOT load it; re-transfer the file."
    log "SHA256 verified: $got"
  else
    warn "no $f.sha256 next to the tar; integrity not verified"
  fi
  docker load -i "$f" >/dev/null || die "docker load failed for $f"
}

# verify_image <version>: exists, linux/amd64, label version matches, never a floating tag
verify_image() {
  local v="$1" id plat lab
  case "$v" in latest|'') die "refusing version '$v'" ;; esac
  id="$(image_id "$v")"; [ -n "$id" ] || die "image $(image_ref "$v") is not present on this host (load it first: --image-tar)"
  plat="$(image_platform "$v")"; [ "$plat" = "linux/amd64" ] || die "image platform is '$plat', expected linux/amd64"
  lab="$(image_label "$v" com.companybi.version)"; [ "$lab" = "$v" ] || die "image label com.companybi.version='$lab' does not match '$v'"
  log "image $(image_ref "$v")  id=$id  platform=$plat"
}

# assert_clean_release <version> <RELEASE file>: production only runs images built from a clean commit.
assert_clean_release() {
  local v="$1" f="$2"
  if [ "$(kv "$f" git_dirty)" = true ]; then
    [ "${COMPANY_BI_ALLOW_DIRTY_RELEASE:-0}" = 1 ] \
      || die "image $v was built from a DIRTY working tree (not traceable to a commit). Rebuild from a clean commit. (test-only override: COMPANY_BI_ALLOW_DIRTY_RELEASE=1 in the environment)"
    warn "accepting a DIRTY build because COMPANY_BI_ALLOW_DIRTY_RELEASE=1 (never do this in production)"
  fi
}

preflight_host() { # preflight_host <need_mysql: yes|no> <app_memory_credit_mib>
  local need_mysql="$1" credit="${2:-0}" app mysql need avail disk
  app="$(to_mib "${COMPANY_BI_APP_MEM_LIMIT:-2g}")"; mysql=0
  [ "$need_mysql" = yes ] && mysql="$(to_mib "${COMPANY_BI_MYSQL_MEM_LIMIT:-768m}")"
  need=$(( app + mysql + 512 )); avail=$(( $(mem_available_mib) + credit ))
  log "memory: need >= ${need} MiB (app ${app} + mysql ${mysql} + 512 headroom), available ${avail} MiB"
  if [ "$avail" -lt "$need" ]; then
    if [ "${COMPANY_BI_SKIP_MEM_CHECK:-0}" = 1 ]; then warn "memory check overridden by COMPANY_BI_SKIP_MEM_CHECK=1"
    else die "not enough free memory on this host. Do NOT free it by touching other services; lower COMPANY_BI_*_MEM_LIMIT or pick another host. (deliberate override: COMPANY_BI_SKIP_MEM_CHECK=1 in the environment)"; fi
  fi
  disk="$(free_mib_at "$COMPANY_BI_HOME")"
  log "disk at COMPANY_BI_HOME: ${disk} MiB free"
  [ "$disk" -ge 5120 ] || die "less than 5 GiB free where COMPANY_BI_HOME lives"
}

wait_container_healthy() { # wait_container_healthy <container> <seconds>
  local c="$1" t="$2" end st
  end=$(( $(date +%s) + t ))
  while [ "$(date +%s)" -lt "$end" ]; do
    [ "$(container_health "$c")" = healthy ] && return 0
    st="$(docker inspect -f '{{.State.Status}}' "$c" 2>/dev/null || echo missing)"
    case "$st" in exited|dead|missing) return 1 ;; esac
    sleep 5
  done
  return 1
}

# switch_app <version>: stop ONLY <project>-app, point compose at <version>, recreate ONLY the app service.
# MySQL is never restarted or recreated here.
switch_app() {
  assert_ours "$APP_C"
  compose stop app >/dev/null 2>&1 || true
  version_env_set "$1"
  compose up -d --no-deps --force-recreate app
}

flyway_rows() { mysql_exec "SELECT COUNT(*) FROM \`$DB_NAME\`.de_standalone_version;" 2>/dev/null | tr -d '[:space:]'; }

# backup_db <label>  -> prints backup file path; dump is consistent (single-transaction), gz, 0600
backup_db() {
  local label="${1:-manual}" out
  container_running "$DB_C" || die "MySQL container $DB_C is not running; cannot back up"
  mkdir -p "$BAK_DIR"; out="$BAK_DIR/${DB_NAME}_$(date +%Y%m%d-%H%M%S)_${label}.sql.gz"
  ( umask 077
    docker exec "$DB_C" sh -c "MYSQL_PWD=\"\$MYSQL_ROOT_PASSWORD\" mysqldump -uroot --single-transaction --routines --triggers --events --hex-blob --default-character-set=utf8mb4 --set-gtid-purged=OFF --databases '$DB_NAME'" \
      | gzip -c > "$out.tmp" ) || { rm -f "$out.tmp"; return 1; }
  gzip -t "$out.tmp" || { rm -f "$out.tmp"; return 1; }
  [ "$(stat -c %s "$out.tmp")" -gt 2048 ] || { rm -f "$out.tmp"; die "backup is suspiciously small"; }
  mv "$out.tmp" "$out"; chmod 600 "$out"
  printf '%s\n' "$out"
}
