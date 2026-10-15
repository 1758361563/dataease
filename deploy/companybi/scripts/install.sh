#!/usr/bin/env bash
# First installation of Company BI (app + dedicated MySQL) as an independent compose project.
#
#   install.sh --init-env                        create .env with random DB passwords / token secret (once)
#   install.sh --version <v> [--image-tar F] [--mysql-image-tar F]
#
# It refuses to run over an existing installation (use upgrade.sh), never touches containers
# that are not <project>-app / <project>-mysql, and never deletes data.
set -euo pipefail
# shellcheck source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

VERSION=""; IMAGE_TAR=""; MYSQL_TAR=""; INIT_ENV=no

usage() { sed -n '2,9p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }
while [ $# -gt 0 ]; do
  case "$1" in
    --version) VERSION="${2:?}"; shift 2 ;;
    --image-tar) IMAGE_TAR="${2:?}"; shift 2 ;;
    --mysql-image-tar) MYSQL_TAR="${2:?}"; shift 2 ;;
    --init-env) INIT_ENV=yes; shift ;;
    -h|--help) usage 0 ;;
    *) echo "unknown option: $1" >&2; usage 2 ;;
  esac
done

rand_hex() { # rand_hex <bytes>
  if command -v openssl >/dev/null 2>&1; then openssl rand -hex "$1"
  else head -c "$1" /dev/urandom | od -An -tx1 | tr -d ' \n'; fi
}

# ---- --init-env -------------------------------------------------------------
if [ "$INIT_ENV" = yes ]; then
  [ -f "$ENV_FILE" ] && die "$ENV_FILE already exists; not overwriting it"
  ( umask 077
    sed -e "s|^COMPANY_BI_DB_PASSWORD=.*|COMPANY_BI_DB_PASSWORD=$(rand_hex 16)|" \
        -e "s|^COMPANY_BI_DB_ROOT_PASSWORD=.*|COMPANY_BI_DB_ROOT_PASSWORD=$(rand_hex 16)|" \
        -e "s|^COMPANY_BI_TOKEN_SECRET=.*|COMPANY_BI_TOKEN_SECRET=$(rand_hex 32)|" \
        "$DEPLOY_DIR/.env.example" > "$ENV_FILE" )
  chmod 600 "$ENV_FILE"
  log "created $ENV_FILE (mode 600)."
  log "Now edit it: set COMPANY_BI_BOOTSTRAP_PASSWORD (>= 12 chars) and review COMPANY_BI_HOME / ports."
  log "BACK THIS FILE UP somewhere safe (outside this host as well): losing it means losing the token secret and DB passwords."
  exit 0
fi

[ -n "$VERSION" ] || { echo "--version is required" >&2; usage 2; }
load_env
check_docker
need_cmds gzip stat df mktemp
acquire_lock

log "== Company BI install: project=$PROJECT version=$VERSION home=$COMPANY_BI_HOME =="

# ---- refuse to run over anything that exists ----------------------------------
for c in "$APP_C" "$DB_C"; do
  if container_exists "$c"; then die "container '$c' already exists. This is not a first install; use upgrade.sh (or pick another COMPANY_BI_PROJECT)."; fi
done
docker network inspect "$NET_N" >/dev/null 2>&1 && die "docker network '$NET_N' already exists; refusing to reuse it"
[ -z "$(state_get CURRENT_VERSION)" ] || die "state says version $(state_get CURRENT_VERSION) is installed; use upgrade.sh"
if [ -d "$COMPANY_BI_HOME/mysql/data" ] && [ -n "$(ls -A "$COMPANY_BI_HOME/mysql/data" 2>/dev/null)" ]; then
  die "$COMPANY_BI_HOME/mysql/data is not empty. Refusing to initialise over existing data. Restore/inspect it manually."
fi
port_in_use "$PORT" && die "TCP port $PORT is already in use on this host"

require_secrets
[ "${#COMPANY_BI_BOOTSTRAP_PASSWORD}" -ge 12 ] || die "COMPANY_BI_BOOTSTRAP_PASSWORD must be set (>= 12 chars) for the first install"
case "$COMPANY_BI_BOOTSTRAP_PASSWORD" in *[\$\`\"\'\\#\ ]*) die "COMPANY_BI_BOOTSTRAP_PASSWORD contains a forbidden character" ;; esac

# ---- images -----------------------------------------------------------------------
[ -n "$MYSQL_TAR" ] && load_image_tar "$MYSQL_TAR"
[ -n "$IMAGE_TAR" ] && { log "loading $IMAGE_TAR"; load_image_tar "$IMAGE_TAR"; }
verify_image "$VERSION"
store_release_files "$VERSION"
assert_clean_release "$VERSION" "$REL_DIR/$VERSION/RELEASE"
docker image inspect "${COMPANY_BI_MYSQL_IMAGE:-mysql:8.4.5}" >/dev/null 2>&1 \
  || die "MySQL image ${COMPANY_BI_MYSQL_IMAGE:-mysql:8.4.5} is not present. Load it first: --mysql-image-tar <file> (shipped in the deploy bundle)."

preflight_host yes 0
ensure_dirs
version_env_set "$VERSION"
compose config -q || die "compose configuration is invalid"

# ---- start --------------------------------------------------------------------------
log "starting MySQL ($DB_C) ..."
compose up -d mysql
wait_container_healthy "$DB_C" 240 || { docker logs --tail 40 "$DB_C" 2>&1 | sed 's/^/  mysql| /' >&2; die "MySQL did not become healthy. Nothing else was started; data dir is kept for inspection."; }

log "starting app ($APP_C) ..."
compose up -d app

log "waiting for the application to come up (first start runs Flyway + company schema, may take several minutes) ..."
if ! "$LIB_DIR/health-check.sh" --wait "${COMPANY_BI_HEALTH_WAIT:-600}"; then
  warn "health check failed. The stack is left running for inspection; data and logs are kept."
  warn "inspect:  docker logs --tail 100 $APP_C   |   scripts/status.sh"
  state_set LAST_OP install LAST_OP_RESULT FAILED LAST_OP_TIME "$(date '+%F %T')"
  history_add install "-" "$VERSION" FAILED "-" "-" "health check failed"
  exit 1
fi

store_release_files "$VERSION"
fp="$(kv "$REL_DIR/$VERSION/SCHEMA" schema.fingerprint)"
state_set CURRENT_VERSION "$VERSION" CURRENT_IMAGE_ID "$(image_id "$VERSION")" CURRENT_SINCE "$(date '+%F %T')" \
          CURRENT_SCHEMA_FP "$fp" PREVIOUS_GOOD_VERSION "" PREVIOUS_GOOD_IMAGE_ID "" \
          LAST_OP install LAST_OP_RESULT OK LAST_OP_TIME "$(date '+%F %T')" PENDING_MANUAL no
history_add install "-" "$VERSION" OK "$fp" "-" "first install"

hr
log "INSTALL OK: $(image_ref "$VERSION") listening on ${BIND}:${PORT} (loopback; use an SSH tunnel)"
log "login as the bootstrap admin (user id 1) with COMPANY_BI_BOOTSTRAP_PASSWORD; then REMOVE that value from .env if you like (it is only read when the admin row is missing)."
log "next: scripts/status.sh | scripts/backup-db.sh"
