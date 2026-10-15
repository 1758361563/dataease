#!/usr/bin/env bash
# Upgrade Company BI to a new image version.
#
#   upgrade.sh --version <new> [--image-tar FILE] [--dry-run] [--yes] [--accept-schema-risk]
#
# Six steps:
#   1 pre-checks        installed state vs. reality, current health, host resources, port, locks
#   2 image + schema    load/verify the new image, compare schema fingerprints, classify risk
#   3 backup            consistent mysqldump + .env + conf BEFORE anything is stopped
#   4 switch            stop <project>-app only, recreate it on the new image (MySQL untouched)
#   5 health check      same checks as health-check.sh, expecting the new version
#   6 record or rollback  success -> state/history; failure -> automatic image rollback ONLY when the
#                         schema is unchanged or the new release declares the old schema compatible;
#                         otherwise stop and print manual steps (the DB is never restored automatically)
#
# --dry-run stops after step 2 and changes nothing. --accept-schema-risk is required when the schema
# changed and compatibility is not declared (or the change contains destructive keywords); it does NOT
# make an automatic rollback safe - rollback then stays manual.
set -euo pipefail
# shellcheck source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

NEW=""; IMAGE_TAR=""; DRY=no; YES=no; ACCEPT=no
while [ $# -gt 0 ]; do
  case "$1" in
    --version) NEW="${2:?}"; shift 2 ;;
    --image-tar) IMAGE_TAR="${2:?}"; shift 2 ;;
    --dry-run) DRY=yes; shift ;;
    --yes) YES=yes; shift ;;
    --accept-schema-risk) ACCEPT=yes; shift ;;
    -h|--help) sed -n '2,19p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
[ -n "$NEW" ] || { echo "--version is required" >&2; exit 2; }

load_env; check_docker; need_cmds gzip stat df mktemp
acquire_lock

OLD="$(state_get CURRENT_VERSION)"
PHASE="precheck"; BACKUP=""; ROLLBACK_MODE=""
on_exit() {
  local rc=$?
  if [ "$rc" -ne 0 ] && { [ "$PHASE" = switching ] || [ "$PHASE" = verifying ] || [ "$PHASE" = rolling_back ]; }; then
    state_set PENDING_MANUAL yes LAST_OP upgrade LAST_OP_RESULT INTERRUPTED LAST_OP_TIME "$(date '+%F %T')" LAST_BACKUP "${BACKUP:--}" || true
    history_add upgrade "$OLD" "$NEW" INTERRUPTED "-" "${BACKUP:--}" "script aborted in phase $PHASE" || true
    log "ERROR: upgrade script aborted while the app was being switched. State is UNKNOWN." >&2
    log "       run scripts/status.sh; pre-upgrade backup: ${BACKUP:--}; see docs/rollback.md" >&2
  fi
}
trap on_exit EXIT

log "== Company BI upgrade: project=$PROJECT  ${OLD:-?} -> $NEW =="

# ---- 1. pre-checks ---------------------------------------------------------------------------
[ -n "$OLD" ] || die "no installed version recorded in $STATE_FILE; run install.sh first"
[ "$NEW" != "$OLD" ] || die "version $NEW is already the current version"
case "$NEW" in latest|'') die "refusing version '$NEW'" ;; esac
[ "$(state_get PENDING_MANUAL)" != yes ] || die "a previous operation left PENDING_MANUAL=yes (see status.sh / docs/rollback.md). Resolve it first."
assert_ours "$APP_C"; assert_ours "$DB_C"
container_exists "$APP_C" || die "$APP_C does not exist; state and reality diverged. Run status.sh."
RUN_VER="$(docker inspect -f '{{index .Config.Labels "com.companybi.version"}}' "$APP_C" 2>/dev/null || true)"
[ "$RUN_VER" = "$OLD" ] || die "recorded version '$OLD' but container runs '$RUN_VER'. Run status.sh and reconcile before upgrading."
container_running "$DB_C" || die "MySQL container $DB_C is not running"
require_secrets
if ! "$LIB_DIR/health-check.sh" >/dev/null 2>&1; then
  [ "$YES" = yes ] && [ "$ACCEPT" = yes ] || die "the CURRENT version is not healthy. Fix or roll back first (health-check.sh shows why). Upgrading a broken system hides which version caused what."
  warn "current version is unhealthy; continuing only because --yes --accept-schema-risk were given"
fi
preflight_host no "$(container_mem_mib "$APP_C")"

# ---- 2. image + schema -------------------------------------------------------------------------
if [ -n "$IMAGE_TAR" ]; then log "loading $IMAGE_TAR"; load_image_tar "$IMAGE_TAR"; fi
verify_image "$NEW"
store_release_files "$NEW"
[ -f "$REL_DIR/$OLD/SCHEMA" ] || store_release_files "$OLD" 2>/dev/null || true
OLD_FP="$(kv "$REL_DIR/$OLD/SCHEMA" schema.fingerprint 2>/dev/null || true)"
NEW_FP="$(kv "$REL_DIR/$NEW/SCHEMA" schema.fingerprint)"
schema_assess "$REL_DIR/$OLD/SCHEMA" "$REL_DIR/$NEW/SCHEMA" "$REL_DIR/$NEW/COMPAT" 2>/dev/null || { SCHEMA_CLASS=UNCONFIRMED; SCHEMA_DESTRUCTIVE=unknown; SCHEMA_DELTA="(old schema manifest unavailable)"; }

hr
log "current : $OLD  image=$(image_id "$OLD")"
log "new     : $NEW  image=$(image_id "$NEW")"
log "git     : $(kv "$REL_DIR/$NEW/RELEASE" git_sha) ($(kv "$REL_DIR/$NEW/RELEASE" git_branch)) built $(kv "$REL_DIR/$NEW/RELEASE" build_time) dirty=$(kv "$REL_DIR/$NEW/RELEASE" git_dirty)"
log "schema  : class=$SCHEMA_CLASS destructive_keywords=$SCHEMA_DESTRUCTIVE"
log "          old fp=${OLD_FP:-?}"
log "          new fp=$NEW_FP"
[ -z "${SCHEMA_DELTA:-}" ] || printf '%s\n' "$SCHEMA_DELTA" | sed 's/^/          delta: /'
hr

assert_clean_release "$NEW" "$REL_DIR/$NEW/RELEASE"

case "$SCHEMA_CLASS" in
  NONE) ROLLBACK_MODE=auto; log "schema unchanged: an image-only rollback is safe." ;;
  DECLARED_COMPATIBLE)
    if [ "$SCHEMA_DESTRUCTIVE" = no ]; then ROLLBACK_MODE=auto; log "schema changed, but $NEW declares the old schema compatible (COMPAT) and no destructive keyword found: image-only rollback allowed."
    else ROLLBACK_MODE=manual; fi ;;
  *) ROLLBACK_MODE=manual ;;
esac
if [ "$ROLLBACK_MODE" = manual ]; then
  warn "schema compatibility with the old version is NOT proven (class=$SCHEMA_CLASS, destructive=$SCHEMA_DESTRUCTIVE)."
  warn "if this upgrade fails, there will be NO automatic rollback; recovery = restore the pre-upgrade backup (manual, docs/rollback.md)."
  [ "$ACCEPT" = yes ] || die "refusing to proceed without --accept-schema-risk. Review the schema delta above (and the SQL in the release) first."
fi

if [ "$DRY" = yes ]; then log "DRY RUN: stopping here. Nothing was changed. rollback_mode_if_upgraded=$ROLLBACK_MODE"; exit 0; fi
if [ "$YES" != yes ]; then confirm "Upgrade $OLD -> $NEW now? The app will be unavailable for a few minutes" || die "aborted by user"; fi

# ---- 3. backup ----------------------------------------------------------------------------------
log "backing up metadata DB ..."
BACKUP="$(backup_db "pre-upgrade-to-$NEW")" || die "DB backup failed; nothing was changed"
log "backup: $BACKUP ($(stat -c %s "$BACKUP") bytes)"
( umask 077; cp "$ENV_FILE" "$BAK_DIR/env_$(date +%Y%m%d-%H%M%S).bak"; tar -C "$COMPANY_BI_HOME/app" -czf "$BAK_DIR/conf_$(date +%Y%m%d-%H%M%S).tar.gz" conf ) || warn "could not back up .env/conf"
ROWS_BEFORE="$(flyway_rows || true)"

# ---- 4. switch ----------------------------------------------------------------------------------
PHASE=switching
log "stopping $APP_C and starting $NEW ..."
switch_app "$NEW"

# ---- 5. health check ----------------------------------------------------------------------------
PHASE=verifying
if "$LIB_DIR/health-check.sh" --wait 600 --expect-version "$NEW"; then
  PHASE=recording
  state_set PREVIOUS_GOOD_VERSION "$OLD" PREVIOUS_GOOD_IMAGE_ID "$(image_id "$OLD")" \
            CURRENT_VERSION "$NEW" CURRENT_IMAGE_ID "$(image_id "$NEW")" CURRENT_SINCE "$(date '+%F %T')" CURRENT_SCHEMA_FP "$NEW_FP" \
            PREVIOUS_SCHEMA_FP "${OLD_FP:-}" ROLLBACK_MODE "$ROLLBACK_MODE" LAST_BACKUP "$BACKUP" \
            LAST_OP upgrade LAST_OP_RESULT OK LAST_OP_TIME "$(date '+%F %T')" PENDING_MANUAL no
  history_add upgrade "$OLD" "$NEW" OK "$SCHEMA_CLASS" "$BACKUP" "rollback_mode=$ROLLBACK_MODE"
  PHASE=done
  hr
  log "UPGRADE OK: $OLD -> $NEW   (rollback mode: $ROLLBACK_MODE)"
  log "old image $(image_ref "$OLD") is kept; nothing was deleted. backup: $BACKUP"
  exit 0
fi

# ---- 6. failure ---------------------------------------------------------------------------------
PHASE=failed
ROWS_AFTER="$(flyway_rows || true)"
hr
log "UPGRADE FAILED: $NEW did not pass the health check."
log "flyway rows before/after: ${ROWS_BEFORE:-?}/${ROWS_AFTER:-?}   backup: $BACKUP"
docker logs --tail 30 "$APP_C" 2>&1 | sed -E 's/((password|passwd|secret|token)[=: "]+)[^ ",;&]+/\1***/Ig' | cut -c1-220 | sed 's/^/  app| /' >&2 || true

if [ "$ROLLBACK_MODE" = auto ]; then
  log "schema is unchanged/declared compatible -> rolling the app image back to $OLD (once; no retry loop) ..."
  PHASE=rolling_back
  switch_app "$OLD"
  if "$LIB_DIR/health-check.sh" --wait 400 --expect-version "$OLD"; then
    state_set LAST_OP upgrade LAST_OP_RESULT FAILED_ROLLED_BACK LAST_OP_TIME "$(date '+%F %T')" LAST_BACKUP "$BACKUP" PENDING_MANUAL no
    history_add upgrade "$OLD" "$NEW" FAILED_ROLLED_BACK "$SCHEMA_CLASS" "$BACKUP" "auto image rollback ok"
    PHASE=done
    log "ROLLED BACK to $OLD, which is healthy again. $NEW is still loaded as an image for investigation."
    exit 1
  fi
  docker stop "$APP_C" >/dev/null 2>&1 || true
  state_set LAST_OP upgrade LAST_OP_RESULT FAILED_ROLLBACK_ALSO_FAILED LAST_OP_TIME "$(date '+%F %T')" LAST_BACKUP "$BACKUP" PENDING_MANUAL yes
  history_add upgrade "$OLD" "$NEW" ROLLBACK_FAILED "$SCHEMA_CLASS" "$BACKUP" "old version unhealthy after rollback; app stopped"
  PHASE=done
  log "ROLLBACK ALSO FAILED. The app is stopped on purpose (no flip-flopping). MySQL is untouched."
  log "next: docker logs $APP_C ; scripts/status.sh ; docs/rollback.md ; backup: $BACKUP"
  exit 1
fi

docker stop "$APP_C" >/dev/null 2>&1 || true
state_set LAST_OP upgrade LAST_OP_RESULT FAILED_MANUAL_ROLLBACK_REQUIRED LAST_OP_TIME "$(date '+%F %T')" LAST_BACKUP "$BACKUP" PENDING_MANUAL yes
history_add upgrade "$OLD" "$NEW" FAILED_MANUAL "$SCHEMA_CLASS" "$BACKUP" "schema compat unproven; app stopped; no auto rollback"
PHASE=done
log "NO AUTOMATIC ROLLBACK: the schema may already have been changed by $NEW, and compatibility with $OLD is not proven."
log "the new app container is stopped; MySQL keeps running and is untouched. Choose ONE:"
log "  a) fix forward: read 'docker logs $APP_C', ship a fixed release, run upgrade.sh again (clear PENDING_MANUAL with rollback.sh --clear-pending after checking)"
log "  b) go back:     scripts/rollback.sh --to $OLD --restore-db $BACKUP    (restores the pre-upgrade dump; DATA WRITTEN SINCE THE BACKUP IS LOST)"
exit 1
